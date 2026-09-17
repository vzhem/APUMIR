//! # Direct transport — один QUIC-endpoint на движок (этап K1, v11.70.24)
//!
//! До v11.70.24 каждое прямое сообщение открывало **новый** endpoint: новый
//! UDP-сокет, новый самоподписанный сертификат, полное рукопожатие TLS 1.3,
//! и всё это под `block_on` в потоке вызывающего (до 10 с). Получатель видел
//! каждое сообщение как новое соединение с нового порта; отображения на NAT
//! жили ровно одно сообщение; внешний адрес спрашивали у STUN с ещё одного,
//! временного сокета - так что в presence уходил порт, за которым никто не
//! слушал.
//!
//! Теперь:
//!
//! - **Один endpoint** (`0.0.0.0:7777`) и для входящих, и для исходящих.
//!   Собеседник видит нас всегда с одного порта - того, который мы
//!   объявляем в presence. Через этот же сокет уходит запрос STUN, поэтому
//!   отражённый адрес - настоящий адрес нашего QUIC.
//! - **Пул соединений** ([`ConnectionPool`]): второе сообщение тому же
//!   узлу идёт по уже открытому соединению - микросекунды вместо сотен
//!   миллисекунд и лишнего трафика рукопожатия.
//! - **Соединения двусторонние.** Входящее соединение от узла `X` после
//!   первого кадра «усыновляется» пулом под ключом `X`: наш ответ `X` идёт
//!   по нему же. Это главное для NAT: если `X` смог дозвониться до нас, мы
//!   отвечаем ему по уже пробитому пути, даже когда сами дозвониться не
//!   смогли бы (симметричный NAT у `X`). Чем больше живых соединений в
//!   сети, тем больше пар достижимы без брокера.
//! - **Keep-alive** раз в 20 с со стороны инициатора: отображение на NAT не
//!   протухает, соединение переживает паузы в разговоре.
//! - **Отправка не держит поток вызывающего** дольше [`DIRECT_SEND_BUDGET`]:
//!   работа идёт в задачах tokio, по одной «полосе» на узел (кадры одному
//!   узлу уходят по порядку, разные узлы - параллельно).
//!
//! Формат кадра не менялся (`sender|msgId|chatId|text` в uni-стриме с
//! префиксом длины), смысл ответа `bool` = «получатель подтвердил приём
//! стрима» - тоже. Старые телефоны принимают такие соединения без
//! изменений: для них это QUIC-клиент, который не закрывает соединение
//! после первого стрима.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::{mpsc, oneshot};

use super::connection_pool::ConnectionPool;
use super::quic_client::{QuicClient, QuicConnection, UdpSideChannel};

/// Сколько вызывающий поток готов ждать ответа «доставлено / нет».
///
/// Раньше было до 10 с (5 на connect + 5 на запись). Если за этот срок
/// ответа нет - отправка считается неудачной, а сообщение уходит запасным
/// путём (брокер / relay-очередь), как и раньше при любой ошибке QUIC.
pub const DIRECT_SEND_BUDGET: Duration = Duration::from_secs(10);

/// Таймаут рукопожатия с узлом, соединения с которым ещё нет.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);

/// Таймаут записи одного стрима по уже открытому соединению (кадр файла -
/// до ~175 КБ, на медленном мобильном канале это секунды).
///
/// Худший случай укладывается в бюджет: мгновенная ошибка на соединении
/// из пула + 4 с рукопожатие + 5 с запись < 10 с. После ТАЙМАУТА на
/// соединении из пула повтора нет (иначе 5 + 4 + 5 > бюджета) - вызывающий
/// получает честный ответ, а не «false, но потом всё-таки дошло».
pub const STREAM_TIMEOUT: Duration = Duration::from_secs(5);

/// Максимум одновременно живых соединений в пуле.
pub const POOL_CAPACITY: usize = 256;

/// Соединение, по которому давно ничего не ходило, закрываем сами -
/// keep-alive иначе держал бы его вечно.
pub const POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// Полоса узла без работы столько времени сворачивается.
const LANE_IDLE_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// Рукопожатие входящего соединения дольше этого - обрываем.
const INBOUND_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// После неудачи с адресом повторные отправки на тот же адрес в этом окне
/// получают `false` сразу, без нового 4-секундного ожидания: отправитель
/// файла шлёт кадры один за другим, и каждый мёртвый адрес стоил бы ему
/// секунд. Окно короткое - узел мог как раз появиться.
const FAIL_FAST_WINDOW: Duration = Duration::from_secs(15);

/// Подряд идущих ошибок чтения на одном соединении больше этого - считаем
/// соединение безнадёжным (страховка от плотного цикла).
const MAX_CONSECUTIVE_READ_ERRORS: u32 = 16;

/// Ёмкость общей очереди команд и очереди одной полосы. При переполнении
/// вызывающий получает `false` сразу - честный отказ лучше накопления.
const COMMAND_QUEUE_CAPACITY: usize = 512;
const LANE_QUEUE_CAPACITY: usize = 64;

/// Обработчик входящего кадра. Получает сырые байты стрима; возвращает
/// идентификатор отправителя (`pk_…`), если кадр разобран и принят, иначе
/// `None`. По нему пул усыновляет входящее соединение.
pub type FrameHandler = Arc<dyn Fn(Vec<u8>) -> Option<String> + Send + Sync + 'static>;

/// Какой стрим открыть под кадр.
///
/// K3: бинарные кадры файлов (`APUF`, `file_wire`) идут с приоритетом
/// данных (FILE_DATA_STREAM_PRIORITY в `quic_client.rs`) и не ждут за
/// собой интерактивные сообщения; формат стрима (длина + payload) и
/// семантика ответа не меняются — старые телефоны принимают такой стрим
/// как очередное сообщение и молча отбрасывают неразобранный кадр.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum JobKind {
    /// Текстовый кадр `sender|msgId|chatId|text` (сообщения, пакеты).
    Interactive,
    /// Бинарный кадр файла (K3).
    FileData,
}

/// Куда вернуть результат отправки.
enum Reply {
    /// Поток вне runtime (Kotlin → uniffi): ждёт на синхронном канале.
    Blocking(std::sync::mpsc::SyncSender<bool>),
    /// Задача внутри runtime.
    Async(oneshot::Sender<bool>),
}

impl Reply {
    fn send(self, sent: bool) {
        match self {
            Reply::Blocking(tx) => {
                let _ = tx.try_send(sent);
            }
            Reply::Async(tx) => {
                let _ = tx.send(sent);
            }
        }
    }
}

/// Одна отправка в полосе узла.
struct SendJob {
    /// Адрес для НОВОГО соединения. `None` - адрес неизвестен: отправка
    /// возможна только по уже живому соединению из пула (например,
    /// усыновлённому входящему от узла за симметричным NAT).
    addr: Option<SocketAddr>,
    payload: Vec<u8>,
    kind: JobKind,
    reply: Reply,
    /// Когда поставлена в очередь. Если вызывающий уже отчаялся ждать
    /// (прошло больше [`DIRECT_SEND_BUDGET`]), кадр не отправляем: он уже
    /// ушёл запасным путём, второй экземпляр только съест канал.
    enqueued_at: Instant,
}

/// Команда транспорту.
enum Command {
    Send {
        peer_id: String,
        job: SendJob,
    },
    /// Забыть соединение с узлом (адрес сменился / узел пропал).
    Forget {
        peer_id: String,
    },
}

/// Общее состояние транспорта.
struct Shared {
    client: Arc<QuicClient>,
    pool: Arc<ConnectionPool>,
    on_frame: FrameHandler,
}

impl Shared {
    /// Усыновить входящее соединение под ключом отправителя, если живого
    /// соединения с ним ещё нет.
    async fn adopt(&self, sender: &str, conn: &QuicConnection) {
        let key = sender.as_bytes().to_vec();
        if self.pool.get(&key).await.is_some() {
            return;
        }
        match self.pool.insert(key, conn.clone()).await {
            Ok(()) => tracing::info!(
                "DIRECT: adopted inbound connection from {} at {}",
                sender,
                conn.remote_address()
            ),
            Err(e) => tracing::debug!("DIRECT: inbound from {} not pooled: {}", sender, e),
        }
    }
}

/// Рукоятка транспорта: живёт в `P2PCore`, клонируется дёшево.
#[derive(Clone)]
pub struct DirectTransport {
    tx: mpsc::Sender<Command>,
    shared: Arc<Shared>,
}

impl DirectTransport {
    /// Поднять транспорт внутри уже работающего tokio-runtime: общий
    /// endpoint, приём входящих соединений, цикл команд.
    ///
    /// Возвращает рукоятку и боковой канал общего сокета (для STUN).
    pub fn start(
        bind_addr: SocketAddr,
        on_frame: FrameHandler,
    ) -> Result<(Self, UdpSideChannel), String> {
        let (client, side) =
            QuicClient::new_with_side_channel(bind_addr).map_err(|e| e.to_string())?;
        let shared = Arc::new(Shared {
            client: Arc::new(client),
            pool: Arc::new(ConnectionPool::with_idle_timeout(
                POOL_CAPACITY,
                POOL_IDLE_TIMEOUT,
            )),
            on_frame,
        });
        let (tx, rx) = mpsc::channel(COMMAND_QUEUE_CAPACITY);
        tokio::spawn(run_accept_loop(Arc::clone(&shared)));
        tokio::spawn(run_command_loop(rx, Arc::clone(&shared)));
        Ok((DirectTransport { tx, shared }, side))
    }

    /// Локальный адрес endpoint'а.
    pub fn local_addr(&self) -> SocketAddr {
        self.shared.client.local_address()
    }

    /// Отправить кадр и дождаться ответа - **блокирующий** вызов для потока
    /// вне runtime (Kotlin → uniffi). Не дольше [`DIRECT_SEND_BUDGET`].
    ///
    /// `true` = получатель подтвердил приём стрима. `false` = не удалось
    /// (нет соединения, таймаут, очередь переполнена) - вызывающий уходит
    /// на запасной путь.
    pub fn send_blocking(
        &self,
        peer_id: &str,
        addr: Option<SocketAddr>,
        payload: Vec<u8>,
    ) -> bool {
        self.send_with_kind_blocking(peer_id, addr, payload, JobKind::Interactive)
    }

    /// K3: бинарный кадр файла (см. [`JobKind::FileData`]). Семантика
    /// ответа та же, что у [`DirectTransport::send_blocking`].
    pub fn send_file_blocking(
        &self,
        peer_id: &str,
        addr: Option<SocketAddr>,
        payload: Vec<u8>,
    ) -> bool {
        self.send_with_kind_blocking(peer_id, addr, payload, JobKind::FileData)
    }

    fn send_with_kind_blocking(
        &self,
        peer_id: &str,
        addr: Option<SocketAddr>,
        payload: Vec<u8>,
        kind: JobKind,
    ) -> bool {
        let (reply_tx, reply_rx) = std::sync::mpsc::sync_channel(1);
        let command = Command::Send {
            peer_id: peer_id.to_string(),
            job: SendJob {
                addr,
                payload,
                kind,
                reply: Reply::Blocking(reply_tx),
                enqueued_at: Instant::now(),
            },
        };
        if let Err(e) = self.tx.try_send(command) {
            tracing::warn!("DIRECT: command queue unavailable ({}), fallback", e);
            return false;
        }
        match reply_rx.recv_timeout(DIRECT_SEND_BUDGET) {
            Ok(sent) => sent,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                tracing::warn!(
                    "DIRECT: no answer within {:?} for {} at {:?}, fallback",
                    DIRECT_SEND_BUDGET,
                    peer_id,
                    addr
                );
                false
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => false,
        }
    }

    /// Асинхронная отправка (для задач внутри runtime, например повторов из
    /// очереди при появлении соседа по mDNS).
    pub async fn send(&self, peer_id: &str, addr: Option<SocketAddr>, payload: Vec<u8>) -> bool {
        self.send_with_kind(peer_id, addr, payload, JobKind::Interactive).await
    }

    async fn send_with_kind(
        &self,
        peer_id: &str,
        addr: Option<SocketAddr>,
        payload: Vec<u8>,
        kind: JobKind,
    ) -> bool {
        let (reply_tx, reply_rx) = oneshot::channel();
        let command = Command::Send {
            peer_id: peer_id.to_string(),
            job: SendJob {
                addr,
                payload,
                kind,
                reply: Reply::Async(reply_tx),
                enqueued_at: Instant::now(),
            },
        };
        if self.tx.send(command).await.is_err() {
            return false;
        }
        match tokio::time::timeout(DIRECT_SEND_BUDGET, reply_rx).await {
            Ok(Ok(sent)) => sent,
            _ => false,
        }
    }

    /// Есть ли живое соединение с узлом (исходящее или усыновлённое).
    pub async fn has_connection(&self, peer_id: &str) -> bool {
        self.shared.pool.get(peer_id.as_bytes()).await.is_some()
    }

    /// Забыть соединение с узлом: следующая отправка откроет новое.
    pub fn forget(&self, peer_id: &str) {
        let _ = self.tx.try_send(Command::Forget {
            peer_id: peer_id.to_string(),
        });
    }

    /// Закрыть endpoint без ожидания (для `stop()`).
    pub fn shutdown(&self) {
        self.shared.client.close_now();
    }
}

// ═══════════════════════════════════════════════════════════════════
// ВХОДЯЩИЕ
// ═══════════════════════════════════════════════════════════════════

/// Приём входящих соединений на общем endpoint.
///
/// Рукопожатие каждого соединения доводится в своей задаче: раньше
/// `accept()` ждал его прямо в цикле, и один медленный клиент задерживал
/// всех остальных, а неудачное рукопожатие (сканер портов, чужой ALPN)
/// вовсе останавливало приём (`break`).
async fn run_accept_loop(shared: Arc<Shared>) {
    tracing::info!("DIRECT: accepting on {}", shared.client.local_address());
    while let Some(connecting) = shared.client.accept_pending().await {
        let shared = Arc::clone(&shared);
        tokio::spawn(async move {
            match tokio::time::timeout(INBOUND_HANDSHAKE_TIMEOUT, connecting).await {
                Ok(Ok(connection)) => {
                    let conn = QuicConnection::new(connection);
                    tracing::info!("QUIC: incoming connection from {}", conn.remote_address());
                    read_loop(conn, shared, true).await;
                }
                Ok(Err(e)) => tracing::warn!("QUIC: inbound handshake failed: {}", e),
                Err(_) => tracing::warn!("QUIC: inbound handshake timed out"),
            }
        });
    }
    tracing::info!("DIRECT: endpoint closed, accept loop exits");
}

/// Чтение кадров с одного соединения - и входящего, и исходящего.
///
/// Исходящие тоже читаем: собеседник (новой версии) отвечает по этому же
/// соединению, а непрочитанные стримы копились бы в буфере до лимита 256.
async fn read_loop(conn: QuicConnection, shared: Arc<Shared>, inbound: bool) {
    let mut adopted_key: Option<Vec<u8>> = None;
    let mut consecutive_errors = 0u32;
    loop {
        let payload = match conn.receive_message().await {
            Ok(p) => {
                consecutive_errors = 0;
                p
            }
            // Ошибка уровня соединения (таймаут, закрытие) - выходим.
            // Ошибка одного стрима (оборван, слишком длинный) - соединение
            // живо, следующие кадры читаем дальше: раньше первый же битый
            // стрим глушил приём от этого узла.
            Err(_) if conn.is_closed() => break,
            Err(e) => {
                consecutive_errors += 1;
                tracing::warn!("QUIC: bad stream from {}: {}", conn.remote_address(), e);
                if consecutive_errors >= MAX_CONSECUTIVE_READ_ERRORS {
                    conn.close(b"too many bad streams");
                    break;
                }
                continue;
            }
        };
        let sender = (shared.on_frame)(payload);
        if !inbound {
            continue;
        }
        let Some(sender) = sender else {
            continue;
        };
        if adopted_key.is_none() {
            adopted_key = Some(sender.as_bytes().to_vec());
            shared.adopt(&sender, &conn).await;
        } else {
            // Живой отправитель - освежаем метку «использовалось», чтобы
            // уборка не закрыла соединение, по которому к нам ходят.
            let _ = shared.pool.get(sender.as_bytes()).await;
        }
    }
    tracing::debug!(
        "DIRECT: connection with {} ended ({})",
        conn.remote_address(),
        if inbound { "inbound" } else { "outbound" }
    );
}

// ═══════════════════════════════════════════════════════════════════
// ИСХОДЯЩИЕ
// ═══════════════════════════════════════════════════════════════════

/// Полоса одного узла: своя очередь и задача-исполнитель.
struct PeerLane {
    tx: mpsc::Sender<SendJob>,
    last_used: Instant,
}

/// Главный цикл: раскладывает команды по полосам узлов, убирает простой.
async fn run_command_loop(mut rx: mpsc::Receiver<Command>, shared: Arc<Shared>) {
    let mut lanes: HashMap<String, PeerLane> = HashMap::new();
    let mut cleanup = tokio::time::interval(Duration::from_secs(60));
    cleanup.tick().await; // первый тик срабатывает сразу - пропускаем
    loop {
        tokio::select! {
            command = rx.recv() => {
                let Some(command) = command else {
                    tracing::info!("DIRECT: command channel closed, transport loop exits");
                    break;
                };
                match command {
                    Command::Send { peer_id, job } => {
                        let lane = lanes
                            .entry(peer_id.clone())
                            .or_insert_with(|| spawn_lane(&peer_id, &shared));
                        lane.last_used = Instant::now();
                        let job = match lane.tx.try_send(job) {
                            Ok(()) => continue,
                            Err(mpsc::error::TrySendError::Full(job)) => {
                                tracing::warn!("DIRECT: lane to {} is full, fallback", peer_id);
                                job.reply.send(false);
                                continue;
                            }
                            Err(mpsc::error::TrySendError::Closed(job)) => job,
                        };
                        // Полоса умерла (не должно случаться) - поднимаем заново.
                        let lane = spawn_lane(&peer_id, &shared);
                        if let Err(e) = lane.tx.try_send(job) {
                            match e {
                                mpsc::error::TrySendError::Full(job)
                                | mpsc::error::TrySendError::Closed(job) => job.reply.send(false),
                            }
                        }
                        lanes.insert(peer_id, lane);
                    }
                    Command::Forget { peer_id } => {
                        lanes.remove(&peer_id);
                        if shared.pool.remove(peer_id.as_bytes()).await {
                            tracing::info!("DIRECT: dropped pooled connection to {}", peer_id);
                        }
                    }
                }
            }
            _ = cleanup.tick() => {
                lanes.retain(|_, lane| lane.last_used.elapsed() < LANE_IDLE_TIMEOUT);
                let removed = shared.pool.cleanup_idle().await;
                if removed > 0 {
                    tracing::info!("DIRECT: closed {} idle connection(s)", removed);
                }
            }
        }
    }
}

fn spawn_lane(peer_id: &str, shared: &Arc<Shared>) -> PeerLane {
    let (tx, mut rx) = mpsc::channel::<SendJob>(LANE_QUEUE_CAPACITY);
    let peer_id = peer_id.to_string();
    let shared = Arc::clone(shared);
    tokio::spawn(async move {
        // Адрес, к которому недавно не удалось ПОДКЛЮЧИТЬСЯ. Ошибки уже
        // открытого соединения сюда не попадают: узел мог перезапуститься по
        // тому же адресу, и новое рукопожатие имеет смысл.
        let mut unreachable: Option<(SocketAddr, Instant)> = None;
        while let Some(job) = rx.recv().await {
            if job.enqueued_at.elapsed() >= DIRECT_SEND_BUDGET {
                tracing::warn!(
                    "DIRECT: frame to {} waited {:?} in lane, caller gave up - dropped",
                    peer_id,
                    job.enqueued_at.elapsed()
                );
                job.reply.send(false);
                continue;
            }
            if let (Some((failed_addr, at)), Some(addr)) = (unreachable, job.addr) {
                if failed_addr == addr
                    && at.elapsed() < FAIL_FAST_WINDOW
                    && shared.pool.get(peer_id.as_bytes()).await.is_none()
                {
                    tracing::debug!(
                        "DIRECT: {} at {} unreachable {:?} ago, fast fallback",
                        peer_id,
                        addr,
                        at.elapsed()
                    );
                    job.reply.send(false);
                    continue;
                }
            }
            let outcome = send_one(&shared, &peer_id, job.addr, &job.payload, job.kind).await;
            unreachable = match (outcome, job.addr) {
                (SendOutcome::ConnectFailed, Some(addr)) => Some((addr, Instant::now())),
                (SendOutcome::Sent, _) => None,
                _ => unreachable,
            };
            job.reply.send(matches!(outcome, SendOutcome::Sent));
        }
    });
    PeerLane {
        tx,
        last_used: Instant::now(),
    }
}

/// Чем кончилась одна отправка.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SendOutcome {
    /// Получатель подтвердил приём стрима.
    Sent,
    /// Соединения нет и открыть не удалось (нет адреса, таймаут, отказ).
    ConnectFailed,
    /// Соединение было, но запись не прошла (обрыв, таймаут записи).
    StreamFailed,
}

/// Откуда взялось соединение для отправки.
struct Acquired {
    conn: QuicConnection,
    /// Только что открыто (а не взято из пула).
    fresh: bool,
    /// Лежит в пуле (иначе - разовое, закрываем после отправки).
    pooled: bool,
}

/// Одна отправка: соединение из пула (или новое), запись стрима; при
/// ошибке на соединении из пула - выбросить его и повторить по новому.
/// Повтор нужен, потому что о смерти соединения quinn узнаёт только по
/// idle-таймауту, а до того пул честно его отдаёт.
async fn send_one(
    shared: &Arc<Shared>,
    peer_id: &str,
    addr: Option<SocketAddr>,
    payload: &[u8],
    kind: JobKind,
) -> SendOutcome {
    let key = peer_id.as_bytes().to_vec();
    for attempt in 1..=2u8 {
        let Some(acquired) = acquire(shared, &key, addr).await else {
            return SendOutcome::ConnectFailed;
        };
        let conn = acquired.conn;
        let mut timed_out = false;
        let write = async {
            match kind {
                JobKind::Interactive => conn.send_message(payload).await,
                JobKind::FileData => conn.send_file_data(payload).await,
            }
        };
        match tokio::time::timeout(STREAM_TIMEOUT, write).await {
            Ok(Ok(())) => {
                tracing::info!(
                    "DIRECT: sent {} bytes to {} at {} ({})",
                    payload.len(),
                    peer_id,
                    conn.remote_address(),
                    if !acquired.pooled {
                        "one-shot"
                    } else if acquired.fresh {
                        "new connection"
                    } else {
                        "pooled"
                    }
                );
                if !acquired.pooled {
                    conn.close(b"one-shot done");
                }
                return SendOutcome::Sent;
            }
            Ok(Err(e)) => tracing::warn!(
                "DIRECT: send to {} at {} failed (attempt {}): {}",
                peer_id,
                conn.remote_address(),
                attempt,
                e
            ),
            Err(_) => {
                timed_out = true;
                tracing::warn!(
                    "DIRECT: send to {} at {} timed out (attempt {})",
                    peer_id,
                    conn.remote_address(),
                    attempt
                );
            }
        }
        // Соединение подвело: закрываем и убираем из пула (именно его, а не
        // новое, успевшее лечь под тот же ключ). Свежее соединение повторять
        // смысла нет - узел недоступен; после таймаута повтор не уложится в
        // бюджет вызывающего.
        conn.close(b"send failed");
        if acquired.pooled {
            shared.pool.remove_if_same(&key, conn.stable_id()).await;
        }
        if acquired.fresh || timed_out {
            return SendOutcome::StreamFailed;
        }
    }
    SendOutcome::StreamFailed
}

/// Живое соединение из пула (любое: исходящее или усыновлённое входящее -
/// адрес из presence лишь подсказка для НОВОГО соединения) или новое.
async fn acquire(
    shared: &Arc<Shared>,
    key: &[u8],
    addr: Option<SocketAddr>,
) -> Option<Acquired> {
    if let Some(existing) = shared.pool.get(key).await {
        return Some(Acquired {
            conn: (*existing).clone(),
            fresh: false,
            pooled: true,
        });
    }

    let Some(addr) = addr else {
        tracing::debug!(
            "DIRECT: no address and no live connection for {}",
            String::from_utf8_lossy(key)
        );
        return None;
    };

    let conn = match tokio::time::timeout(
        CONNECT_TIMEOUT,
        shared.client.connect(addr, "p2p-messenger"),
    )
    .await
    {
        Ok(Ok(conn)) => conn,
        Ok(Err(e)) => {
            tracing::warn!("DIRECT: connect to {} failed: {}", addr, e);
            return None;
        }
        Err(_) => {
            tracing::warn!("DIRECT: connect to {} timed out", addr);
            return None;
        }
    };
    tracing::info!("QUIC connect ok to {}", addr);

    // Читаем и исходящее соединение: собеседник может отвечать по нему.
    tokio::spawn(read_loop(conn.clone(), Arc::clone(shared), false));

    let pooled = match shared.pool.insert(key.to_vec(), conn.clone()).await {
        Ok(()) => true,
        Err(_) => {
            shared.pool.cleanup_idle().await;
            shared.pool.insert(key.to_vec(), conn.clone()).await.is_ok()
        }
    };
    if !pooled {
        tracing::warn!("DIRECT: pool full, one-shot connection to {}", addr);
    }
    Some(Acquired {
        conn,
        fresh: true,
        pooled,
    })
}

// ═══════════════════════════════════════════════════════════════════
// STUN С QUIC-ПОРТА
// ═══════════════════════════════════════════════════════════════════

/// Спросить у STUN-сервера наш внешний адрес **с общего QUIC-сокета**.
///
/// Серверы пробуются по очереди, каждый с таймаутом `per_server`. Ответы
/// приходят через боковой канал (обёртка сокета отводит всё, что похоже на
/// STUN, см. `SharedUdpSocket`).
pub async fn stun_via_side_channel(
    side: &mut UdpSideChannel,
    servers: &[&str],
    per_server: Duration,
) -> Option<SocketAddr> {
    use super::ice::{decode_binding_response, encode_binding_request};
    use std::net::ToSocketAddrs;

    for server in servers {
        let server_owned = server.to_string();
        // DNS-резолв блокирующий - выносим из реактора.
        let resolved = tokio::task::spawn_blocking(move || {
            server_owned
                .to_socket_addrs()
                .ok()
                .and_then(|mut it| it.find(|a| a.is_ipv4()))
        })
        .await
        .ok()
        .flatten();
        let Some(target) = resolved else {
            tracing::warn!("STUN(7777): cannot resolve {}", server);
            continue;
        };
        let request = match encode_binding_request() {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("STUN(7777): encode failed: {}", e);
                return None;
            }
        };
        side.drain();
        if let Err(e) = side.send_to(target, &request).await {
            tracing::warn!("STUN(7777): send to {} failed: {}", server, e);
            continue;
        }
        let deadline = tokio::time::Instant::now() + per_server;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                tracing::warn!("STUN(7777): {} timed out", server);
                break;
            }
            match tokio::time::timeout(remaining, side.recv()).await {
                Ok(Some((from, bytes))) => {
                    if from != target {
                        // Чужая/запоздалая датаграмма - не наш ответ.
                        continue;
                    }
                    match decode_binding_response(&bytes) {
                        Ok(addr) => {
                            tracing::info!("STUN(7777): {} sees us as {}", server, addr);
                            return Some(addr);
                        }
                        Err(e) => {
                            tracing::warn!("STUN(7777): bad response from {}: {}", server, e);
                            break;
                        }
                    }
                }
                Ok(None) => return None, // сокет закрыт
                Err(_) => {
                    tracing::warn!("STUN(7777): {} timed out", server);
                    break;
                }
            }
        }
    }
    None
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    fn any_port() -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 0)
    }

    /// Обработчик, который складывает кадры в канал и считает отправителем
    /// `sender` (в реальном ядре отправитель берётся из самого кадра).
    fn recording_handler(
        sender: &'static str,
    ) -> (FrameHandler, mpsc::UnboundedReceiver<Vec<u8>>) {
        let (tx, rx) = mpsc::unbounded_channel();
        let handler: FrameHandler = Arc::new(move |payload: Vec<u8>| {
            let _ = tx.send(payload);
            Some(sender.to_string())
        });
        (handler, rx)
    }

    fn noop_handler() -> FrameHandler {
        Arc::new(|_payload: Vec<u8>| -> Option<String> { None })
    }

    /// Два кадра одному узлу идут по ОДНОМУ соединению: сервер принимает
    /// ровно одно соединение и читает из него оба сообщения.
    #[tokio::test]
    async fn second_send_reuses_pooled_connection() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();

        let server_clone = Arc::clone(&server);
        let server_task = tokio::spawn(async move {
            let conn = server_clone.accept().await.unwrap();
            let first = conn.receive_message().await.unwrap();
            let second = conn.receive_message().await.unwrap();
            (first, second)
        });

        assert!(transport.send("pk_peer", Some(server_addr), b"one".to_vec()).await);
        assert!(transport.send("pk_peer", Some(server_addr), b"two".to_vec()).await);

        let (first, second) = tokio::time::timeout(Duration::from_secs(5), server_task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(first, b"one");
        assert_eq!(second, b"two");
        println!("✅ Второй кадр ушёл по соединению из пула");
    }

    /// K3: бинарный кадр файла уезжает тем же образом (пул, один кадр =
    /// один стрим), байты доходят без искажений — сервер видит магик APUF.
    #[tokio::test]
    async fn file_frame_goes_through_pool_as_binary() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();

        let mut frame = vec![b'A', b'P', b'U', b'F', 1, 2, 0, 0, 0, 0, 0, 25];
        frame.extend_from_slice(&vec![0xA5u8; 13_000]);

        let server_clone = Arc::clone(&server);
        let server_task = tokio::spawn(async move {
            let conn = server_clone.accept().await.unwrap();
            let first = conn.receive_message().await.unwrap();
            let second = conn.receive_message().await.unwrap();
            (first, second)
        });

        assert!(transport.send_file_blocking("pk_peer", Some(server_addr), frame.clone()));
        // Второй кадр тому же узлу — по тому же соединению из пула.
        assert!(transport.send_file_blocking("pk_peer", Some(server_addr), frame.clone()));

        let (first, second) = tokio::time::timeout(Duration::from_secs(5), server_task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(first, frame);
        assert_eq!(second, frame);
        println!("✅ Бинарные кадры APUF прошли через транспорт (пул)");
    }

    /// Двусторонность: A дозвонился до B, B отвечает A по тому же
    /// соединению - даже если «адрес A» у B заведомо неверный.
    #[tokio::test]
    async fn inbound_connection_is_reused_for_reply() {
        let (handler_a, mut frames_a) = recording_handler("pk_b");
        let (handler_b, mut frames_b) = recording_handler("pk_a");
        let (a, _side_a) = DirectTransport::start(any_port(), handler_a).unwrap();
        let (b, _side_b) = DirectTransport::start(any_port(), handler_b).unwrap();

        assert!(a.send("pk_b", Some(b.local_addr()), b"hi from A".to_vec()).await);
        let got = tokio::time::timeout(Duration::from_secs(5), frames_b.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(got, b"hi from A");

        // Усыновление происходит после разбора первого кадра - даём мгновение.
        tokio::time::sleep(Duration::from_millis(100)).await;

        // Адреса A у B «нет» (None): единственный путь - усыновлённое
        // входящее соединение. Успех = ответ ушёл по нему.
        assert!(b.has_connection("pk_a").await);
        assert!(b.send("pk_a", None, b"hi back from B".to_vec()).await);
        let got = tokio::time::timeout(Duration::from_secs(5), frames_a.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(got, b"hi back from B");
        println!("✅ Ответ ушёл по входящему соединению без нового рукопожатия");
    }

    /// Ни адреса, ни соединения → `false` сразу.
    #[tokio::test]
    async fn no_address_and_no_connection_fails_fast() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let started = Instant::now();
        assert!(!transport.send("pk_unknown", None, b"?".to_vec()).await);
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    /// Никто не слушает → `false` не позже бюджета, без паники.
    #[tokio::test]
    async fn unreachable_peer_returns_false_within_budget() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        // Порт 9 (discard) на loopback: почти наверняка никто не слушает UDP.
        let dead = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 9);
        let started = Instant::now();
        let sent = transport.send("pk_dead", Some(dead), b"hello?".to_vec()).await;
        assert!(!sent);
        assert!(started.elapsed() < DIRECT_SEND_BUDGET + Duration::from_secs(1));
        println!("✅ Недоступный узел: false за {:?}", started.elapsed());
    }

    /// Блокирующая отправка с чужого потока (как из Kotlin через uniffi):
    /// не паникует и возвращает результат.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn blocking_send_from_foreign_thread() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let s = Arc::clone(&server);
        let server_task = tokio::spawn(async move {
            let conn = s.accept().await.unwrap();
            conn.receive_message().await.unwrap()
        });

        let t = transport.clone();
        let sent = std::thread::spawn(move || {
            t.send_blocking("pk_peer", Some(server_addr), b"blocking".to_vec())
        })
        .join()
        .unwrap();
        assert!(sent);
        assert_eq!(server_task.await.unwrap(), b"blocking");
        println!("✅ Блокирующая отправка с чужого потока работает");
    }

    /// Второй кадр на мёртвый адрес отвечает `false` сразу (окно
    /// быстрого отказа), а не после нового ожидания рукопожатия.
    #[tokio::test]
    async fn repeated_failure_is_fast() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let dead = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 9);
        assert!(!transport.send("pk_dead", Some(dead), b"1".to_vec()).await);
        let started = Instant::now();
        assert!(!transport.send("pk_dead", Some(dead), b"2".to_vec()).await);
        assert!(started.elapsed() < Duration::from_secs(1));
        println!("✅ Повторный отказ за {:?}", started.elapsed());
    }

    /// `forget` закрывает соединение из пула: следующая отправка идёт по
    /// новому (сервер принимает второе соединение).
    #[tokio::test]
    async fn forget_drops_pooled_connection() {
        let (transport, _side) = DirectTransport::start(any_port(), noop_handler()).unwrap();
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let s = Arc::clone(&server);
        let server_task = tokio::spawn(async move {
            let first = s.accept().await.unwrap();
            let m1 = first.receive_message().await.unwrap();
            let second = s.accept().await.unwrap();
            let m2 = second.receive_message().await.unwrap();
            (m1, m2)
        });

        assert!(transport.send("pk_x", Some(server_addr), b"1".to_vec()).await);
        // Даём серверу дочитать первый кадр до закрытия соединения.
        tokio::time::sleep(Duration::from_millis(300)).await;
        transport.forget("pk_x");
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert!(transport.send("pk_x", Some(server_addr), b"2".to_vec()).await);

        let (m1, m2) = tokio::time::timeout(Duration::from_secs(5), server_task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(m1, b"1");
        assert_eq!(m2, b"2");
        println!("✅ forget → новое соединение");
    }
}
