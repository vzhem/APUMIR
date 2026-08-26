//! # QUIC Client — обёртка над `quinn`
//!
//! Предоставляет удобный API для установки QUIC-соединений и обмена данными.
//!
//! ## Что даёт QUIC:
//!
//! - **Встроенное TLS 1.3** — трафик всегда зашифрован на транспортном уровне
//! - **Мультиплексирование стримов** — несколько логических каналов внутри одного соединения
//! - **Connection Migration** — соединение выживает при смене IP (WiFi → 4G)
//! - **0-RTT возобновление** — быстрое переподключение к тем же узлам
//! - **Отсутствие head-of-line blocking** — потеря пакета не блокирует другие стримы
//!
//! ## Что мы делаем в MVP:
//!
//! - Самоподписанные TLS-сертификаты (генерируются на каждый запуск)
//! - Клиент принимает ЛЮБОЙ сертификат (аутентификация на E2E уровне через Ed25519)
//! - Простой API: `send_message()`, `receive_message()`

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use quinn::{ClientConfig, Connection, Endpoint, RecvStream, SendStream, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum QuicClientError {
    #[error("Ошибка создания endpoint: {0}")]
    EndpointCreation(String),

    #[error("Ошибка подключения к {addr}: {reason}")]
    ConnectionFailed { addr: String, reason: String },

    #[error("Ошибка открытия стрима: {0}")]
    StreamOpen(String),

    #[error("Ошибка отправки данных: {0}")]
    SendFailed(String),

    #[error("Ошибка приёма данных: {0}")]
    ReceiveFailed(String),

    #[error("Ошибка конфигурации TLS: {0}")]
    TlsConfig(String),

    #[error("Соединение закрыто")]
    ConnectionClosed,
}

pub type QuicResult<T> = Result<T, QuicClientError>;

// ═══════════════════════════════════════════════════════════════════
// QUIC CONNECTION — обёртка над quinn::Connection
// ═══════════════════════════════════════════════════════════════════

/// Активное QUIC-соединение с одним узлом.
///
/// Держит внутри `quinn::Connection`, предоставляет удобные методы
/// для отправки/приёма сообщений через bidirectional streams.
pub struct QuicConnection {
    inner: Connection,
    remote_addr: SocketAddr,
}

impl QuicConnection {
    /// Создать обёртку из quinn::Connection.
    pub fn new(conn: Connection) -> Self {
        let remote_addr = conn.remote_address();
        QuicConnection {
            inner: conn,
            remote_addr,
        }
    }

    /// Удалённый адрес узла.
    pub fn remote_address(&self) -> SocketAddr {
        self.remote_addr
    }

    /// Отправить сообщение (одно сообщение = один uni-directional stream).
    ///
    /// Используется для fire-and-forget: отправили и забыли.
    /// Если нужен ответ — используйте `send_and_receive`.
    ///
    /// # Формат
    /// - 4 байта: длина payload (little-endian u32)
    /// - N байт: сам payload
    pub async fn send_message(&self, payload: &[u8]) -> QuicResult<()> {
        // Открываем uni-directional stream (только отправка)
        let mut send = self
            .inner
            .open_uni()
            .await
            .map_err(|e| QuicClientError::StreamOpen(e.to_string()))?;

        // Отправляем длину + payload
        write_length_prefixed(&mut send, payload).await?;

        // Закрываем стрим (сигнал EOF)
        // finish() отправляет FIN и данные будут доставлены пока
        // соединение живо — за это отвечает вызывающий код.
        send.finish()
            .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;

        // Wait for receiver acknowledgment (FIN acknowledged).
        // Without this, data is lost when endpoint is dropped in send_via_quic.
        send.stopped()
            .await
            .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;

        Ok(())
    }

    /// Принять одно сообщение (ожидает открытия входящего uni stream).
    pub async fn receive_message(&self) -> QuicResult<Vec<u8>> {
        // Принимаем входящий uni-directional stream
        let mut recv = self
            .inner
            .accept_uni()
            .await
            .map_err(|e| QuicClientError::ReceiveFailed(e.to_string()))?;

        read_length_prefixed(&mut recv).await
    }

    /// Отправить запрос и получить ответ в том же bidirectional stream.
    ///
    /// Используется для request-response взаимодействия (например DHT queries).
    pub async fn send_and_receive(&self, payload: &[u8]) -> QuicResult<Vec<u8>> {
        // Открываем bidirectional stream
        let (mut send, mut recv) = self
            .inner
            .open_bi()
            .await
            .map_err(|e| QuicClientError::StreamOpen(e.to_string()))?;

        // Отправляем запрос
        write_length_prefixed(&mut send, payload).await?;
        send.finish()
            .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;

        // Читаем ответ
        read_length_prefixed(&mut recv).await
    }

    /// Принять входящий bidirectional запрос, обработать через handler, отправить ответ.
    ///
    /// `handler` — функция которая принимает request и возвращает response.
    pub async fn accept_request<F>(&self, handler: F) -> QuicResult<()>
    where
        F: FnOnce(Vec<u8>) -> Vec<u8>,
    {
        let (mut send, mut recv) = self
            .inner
            .accept_bi()
            .await
            .map_err(|e| QuicClientError::ReceiveFailed(e.to_string()))?;

        let request = read_length_prefixed(&mut recv).await?;
        let response = handler(request);

        write_length_prefixed(&mut send, &response).await?;
        send.finish()
            .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;

        Ok(())
    }

    /// Закрыть соединение с указанием причины.
    pub fn close(&self, reason: &[u8]) {
        // Код 0 = штатное закрытие
        self.inner.close(0u32.into(), reason);
    }

    /// Проверить, закрыто ли соединение.
    pub fn is_closed(&self) -> bool {
        self.inner.close_reason().is_some()
    }
}

// ═══════════════════════════════════════════════════════════════════
// QUIC CLIENT — фабрика соединений
// ═══════════════════════════════════════════════════════════════════

/// Основной клиент QUIC для приложения.
///
/// Держит:
/// - Endpoint (объединяет клиента и сервера в одном сокете)
/// - Локальный адрес для входящих соединений
pub struct QuicClient {
    endpoint: Endpoint,
    local_addr: SocketAddr,
}

impl QuicClient {
    /// Создать новый QUIC endpoint, привязанный к указанному порту.
    ///
    /// # Аргументы
    /// - `bind_addr` — адрес для привязки, обычно `0.0.0.0:0` (любой порт)
    ///   или `0.0.0.0:7777` (наш стандартный порт)
    pub fn new(bind_addr: SocketAddr) -> QuicResult<Self> {
        // Устанавливаем криптопровайдер aws-lc-rs как default
        // (нужно один раз на процесс — второй вызов будет проигнорирован)
        let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

        // Генерируем самоподписанный сертификат
        let (cert, key) = generate_self_signed_cert()?;

        // Настройка сервера (для входящих соединений)
        let server_config = make_server_config(cert.clone(), key)?;

        // Настройка клиента (для исходящих соединений)
        let client_config = make_client_config()?;

        // Создаём endpoint
        let mut endpoint = Endpoint::server(server_config, bind_addr)
            .map_err(|e| QuicClientError::EndpointCreation(e.to_string()))?;

        // Устанавливаем клиентскую конфигурацию (для исходящих connect())
        endpoint.set_default_client_config(client_config);

        let local_addr = endpoint
            .local_addr()
            .map_err(|e| QuicClientError::EndpointCreation(e.to_string()))?;

        Ok(QuicClient {
            endpoint,
            local_addr,
        })
    }

    /// Локальный адрес на котором мы слушаем.
    pub fn local_address(&self) -> SocketAddr {
        self.local_addr
    }

    /// Установить исходящее соединение с удалённым узлом.
    ///
    /// # Аргументы
    /// - `addr` — адрес удалённого узла
    /// - `server_name` — SNI имя (в MVP используем "p2p-messenger")
    pub async fn connect(&self, addr: SocketAddr, server_name: &str) -> QuicResult<QuicConnection> {
        let connecting = self.endpoint.connect(addr, server_name).map_err(|e| {
            QuicClientError::ConnectionFailed {
                addr: addr.to_string(),
                reason: e.to_string(),
            }
        })?;

        let connection = connecting
            .await
            .map_err(|e| QuicClientError::ConnectionFailed {
                addr: addr.to_string(),
                reason: e.to_string(),
            })?;

        Ok(QuicConnection::new(connection))
    }

    /// Принять следующее входящее соединение.
    ///
    /// Обычно вызывается в цикле в отдельной задаче.
    pub async fn accept(&self) -> QuicResult<QuicConnection> {
        let incoming = self
            .endpoint
            .accept()
            .await
            .ok_or_else(|| QuicClientError::EndpointCreation("endpoint closed".into()))?;

        let connection = incoming
            .await
            .map_err(|e| QuicClientError::ConnectionFailed {
                addr: "incoming".to_string(),
                reason: e.to_string(),
            })?;

        Ok(QuicConnection::new(connection))
    }

    /// Закрыть endpoint (graceful shutdown).
    pub async fn close(&self) {
        self.endpoint.close(0u32.into(), b"shutdown");
        // Ждём завершения всех соединений (с таймаутом)
        let _ = tokio::time::timeout(Duration::from_secs(5), self.endpoint.wait_idle()).await;
    }
}

// ═══════════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Записать в стрим сообщение с префиксом длины.
async fn write_length_prefixed(send: &mut SendStream, payload: &[u8]) -> QuicResult<()> {
    let len = payload.len() as u32;
    send.write_all(&len.to_le_bytes())
        .await
        .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;
    send.write_all(payload)
        .await
        .map_err(|e| QuicClientError::SendFailed(e.to_string()))?;
    Ok(())
}

/// Прочитать сообщение с префиксом длины.
async fn read_length_prefixed(recv: &mut RecvStream) -> QuicResult<Vec<u8>> {
    // Читаем 4 байта длины
    let mut len_buf = [0u8; 4];
    recv.read_exact(&mut len_buf)
        .await
        .map_err(|e| QuicClientError::ReceiveFailed(e.to_string()))?;
    let len = u32::from_le_bytes(len_buf) as usize;

    // Защита от gigantic сообщений (16 MB максимум как в protocol)
    if len > 16 * 1024 * 1024 {
        return Err(QuicClientError::ReceiveFailed(format!(
            "Сообщение слишком большое: {} байт",
            len
        )));
    }

    // Читаем payload
    let mut payload = vec![0u8; len];
    recv.read_exact(&mut payload)
        .await
        .map_err(|e| QuicClientError::ReceiveFailed(e.to_string()))?;

    Ok(payload)
}

/// Сгенерировать самоподписанный сертификат для QUIC.
/// В MVP это ОК, потому что аутентификация узлов идёт на E2E уровне через Ed25519.
fn generate_self_signed_cert() -> QuicResult<(CertificateDer<'static>, PrivatePkcs8KeyDer<'static>)>
{
    let cert = rcgen::generate_simple_self_signed(vec!["p2p-messenger".to_string()])
        .map_err(|e| QuicClientError::TlsConfig(e.to_string()))?;

    let cert_der = CertificateDer::from(cert.cert.der().to_vec());
    let key_der = PrivatePkcs8KeyDer::from(cert.key_pair.serialize_der());

    Ok((cert_der, key_der))
}

/// Конфигурация сервера (для приёма входящих соединений).
fn make_server_config(
    cert: CertificateDer<'static>,
    key: PrivatePkcs8KeyDer<'static>,
) -> QuicResult<ServerConfig> {
    // Сначала создаём rustls-конфиг вручную чтобы установить ALPN
    let mut server_crypto = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key.into())
        .map_err(|e| QuicClientError::TlsConfig(e.to_string()))?;

    // ═══ КРИТИЧНО: ALPN должен совпадать с клиентом! ═══
    server_crypto.alpn_protocols = vec![b"p2p-msg-v1".to_vec()];

    // Оборачиваем в QUIC-совместимую конфигурацию
    let quic_server_config = quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)
        .map_err(|e| QuicClientError::TlsConfig(e.to_string()))?;

    // ═══ ВАЖНО: сначала создаём transport, потом ServerConfig ═══
    let mut transport = quinn::TransportConfig::default();
    transport.max_concurrent_uni_streams(256_u32.into());
    transport.max_concurrent_bidi_streams(256_u32.into());
    transport.max_idle_timeout(Some(Duration::from_secs(300).try_into().unwrap()));

    let mut server_config = ServerConfig::with_crypto(Arc::new(quic_server_config));
    server_config.transport_config(Arc::new(transport));

    Ok(server_config)
}

/// Конфигурация клиента (для исходящих соединений).
///
/// **ВАЖНО:** принимает ЛЮБОЙ сертификат.
/// Аутентификация узлов идёт на E2E уровне через Ed25519 в handshake.
fn make_client_config() -> QuicResult<ClientConfig> {
    let mut client_crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(SkipServerVerification::new()))
        .with_no_client_auth();

    // Обязательно для QUIC
    client_crypto.alpn_protocols = vec![b"p2p-msg-v1".to_vec()];

    let quic_client_config = quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto)
        .map_err(|e| QuicClientError::TlsConfig(e.to_string()))?;

    let mut client_config = ClientConfig::new(Arc::new(quic_client_config));

    // Настройки транспорта
    let mut transport = quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(Duration::from_secs(300).try_into().unwrap()));
    transport.max_concurrent_uni_streams(256_u32.into());
    transport.max_concurrent_bidi_streams(256_u32.into());
    client_config.transport_config(Arc::new(transport));

    Ok(client_config)
}

// ═══════════════════════════════════════════════════════════════════
// VERIFIER — принимает ЛЮБОЙ сертификат (только для MVP!)
// ═══════════════════════════════════════════════════════════════════

/// Верификатор который принимает любой сертификат.
///
/// Это безопасно ТОЛЬКО потому что мы аутентифицируем узлы через Ed25519
/// на прикладном уровне (в X3DH handshake, см. crypto/handshake.rs).
/// QUIC/TLS даёт только защиту от пассивного перехвата, но не аутентификацию.
#[derive(Debug)]
struct SkipServerVerification(Arc<rustls::crypto::CryptoProvider>);

impl SkipServerVerification {
    fn new() -> Self {
        Self(Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
    }
}

impl rustls::client::danger::ServerCertVerifier for SkipServerVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.0.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.0.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    /// Хелпер: адрес loopback с указанным портом
    fn loopback(port: u16) -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), port)
    }

    /// Хелпер: любой свободный порт на loopback
    fn any_port() -> SocketAddr {
        loopback(0)
    }

    #[tokio::test]
    async fn test_create_endpoint() {
        let client = QuicClient::new(any_port()).unwrap();
        let addr = client.local_address();

        assert_eq!(addr.ip(), IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
        assert!(addr.port() > 0);
        println!("✅ QUIC endpoint создан на {}", addr);
    }

    #[tokio::test]
    async fn test_two_endpoints_connect() {
        // Создаём "сервер"
        let server = QuicClient::new(any_port()).unwrap();
        let server_addr = server.local_address();

        // Создаём "клиента"
        let client = QuicClient::new(any_port()).unwrap();

        // Сервер принимает соединение в отдельной задаче
        let server_task = tokio::spawn(async move {
            let conn = server.accept().await.unwrap();
            conn.remote_address()
        });

        // Клиент подключается
        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();
        assert_eq!(client_conn.remote_address(), server_addr);

        // Сервер должен был получить соединение
        let client_addr_on_server = server_task.await.unwrap();
        assert_eq!(client_addr_on_server, client.local_address());

        println!("✅ Два endpoint успешно установили QUIC-соединение");
    }

    #[tokio::test]
    async fn test_send_and_receive_message() {
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        // Сервер принимает и читает сообщение
        let server_clone = server.clone();
        let server_task = tokio::spawn(async move {
            let conn = server_clone.accept().await.unwrap();
            let received = conn.receive_message().await.unwrap();
            // Держим соединение открытым чтобы данные точно дошли
            tokio::time::sleep(Duration::from_millis(100)).await;
            received
        });

        // Клиент подключается и отправляет
        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();
        client_conn.send_message(b"Hello P2P!").await.unwrap();

        // Небольшая пауза чтобы данные точно долетели до сервера
        tokio::time::sleep(Duration::from_millis(50)).await;

        // Проверяем что сервер получил
        let received = server_task.await.unwrap();
        assert_eq!(received, b"Hello P2P!");

        println!("✅ Сообщение прошло через QUIC-стрим");
    }

    #[tokio::test]
    async fn test_send_large_message() {
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        // 1 MB данных
        let large_data = vec![42u8; 1_048_576];
        let expected = large_data.clone();

        let server_clone = server.clone();
        let server_task = tokio::spawn(async move {
            let conn = server_clone.accept().await.unwrap();
            let received = conn.receive_message().await.unwrap();
            // Держим соединение открытым
            tokio::time::sleep(Duration::from_millis(100)).await;
            received
        });

        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();
        client_conn.send_message(&large_data).await.unwrap();

        // Пауза для доставки большого объёма данных
        tokio::time::sleep(Duration::from_millis(200)).await;

        let received = server_task.await.unwrap();
        assert_eq!(received.len(), expected.len());
        assert_eq!(received, expected);

        println!("✅ 1 MB сообщение прошло через QUIC");
    }

    #[tokio::test]
    async fn test_multiple_messages_same_connection() {
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        let server_clone = server.clone();
        let server_task = tokio::spawn(async move {
            let conn = Arc::new(server_clone.accept().await.unwrap());
            let mut received = Vec::new();
            for _ in 0..5 {
                received.push(conn.receive_message().await.unwrap());
            }
            // Держим соединение открытым
            tokio::time::sleep(Duration::from_millis(100)).await;
            received
        });

        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();

        // Отправляем 5 сообщений подряд по одному соединению
        for i in 0..5 {
            let msg = format!("Message {}", i);
            client_conn.send_message(msg.as_bytes()).await.unwrap();
        }

        // Пауза чтобы все данные дошли
        tokio::time::sleep(Duration::from_millis(100)).await;

        let received = server_task.await.unwrap();
        assert_eq!(received.len(), 5);
        for (i, msg) in received.iter().enumerate() {
            let expected = format!("Message {}", i);
            assert_eq!(msg, expected.as_bytes());
        }

        println!("✅ 5 сообщений через одно соединение");
    }

    #[tokio::test]
    async fn test_connect_to_nonexistent_server_fails() {
        let client = QuicClient::new(any_port()).unwrap();

        // Пробуем подключиться к порту, где никого нет
        let bad_addr = loopback(1); // Порт 1 — скорее всего никто не слушает

        let result = tokio::time::timeout(
            Duration::from_secs(3),
            client.connect(bad_addr, "p2p-messenger"),
        )
        .await;

        // Ожидаем либо таймаут, либо ошибку подключения
        match result {
            Ok(Err(_)) => println!("✅ Подключение к несуществующему серверу отклонено"),
            Err(_) => println!("✅ Таймаут при подключении к несуществующему серверу"),
            Ok(Ok(_)) => panic!("Не должно было подключиться!"),
        }
    }

    #[tokio::test]
    async fn test_client_close_graceful() {
        let client = QuicClient::new(any_port()).unwrap();
        client.close().await;
        println!("✅ Graceful shutdown работает");
    }

    #[tokio::test]
    async fn test_bidirectional_communication() {
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        // Сервер: принимает соединение, обрабатывает bidirectional запрос
        let server_clone = server.clone();
        let server_task = tokio::spawn(async move {
            let conn = server_clone.accept().await.unwrap();
            conn.accept_request(|request| {
                assert_eq!(request, b"PING");
                b"PONG".to_vec()
            })
            .await
            .unwrap();
            // Держим соединение открытым до конца теста
            tokio::time::sleep(Duration::from_millis(100)).await;
        });

        // Клиент отправляет запрос и получает ответ в одном стриме
        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();
        let response = client_conn.send_and_receive(b"PING").await.unwrap();
        assert_eq!(response, b"PONG");

        server_task.await.unwrap();
        println!("✅ Двусторонняя связь: PING → PONG");
    }
}
