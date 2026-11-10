//! Local MQTT 3.1.1 brokers exercise the real dual-session transport without
//! external services. These are not tests of TLS, real VPNs, NAT or Android.

use super::*;
use std::net::SocketAddr;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::OwnedReadHalf;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio::time::timeout;

#[derive(Clone)]
struct Listener {
    tx: mpsc::UnboundedSender<Vec<u8>>,
    subscribed: Arc<AtomicBool>,
}

type Listeners = Arc<StdMutex<Vec<Listener>>>;
type Publication = (String, Vec<u8>);

struct TestBroker {
    addr: SocketAddr,
    publications: mpsc::UnboundedReceiver<Publication>,
    subscriptions: Arc<AtomicU64>,
    listeners: Listeners,
    task: JoinHandle<()>,
}

impl Drop for TestBroker {
    fn drop(&mut self) { self.task.abort(); }
}

impl TestBroker {
    async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let listeners: Listeners = Arc::new(StdMutex::new(Vec::new()));
        let task_listeners = Arc::clone(&listeners);
        let subscriptions = Arc::new(AtomicU64::new(0));
        let subscription_counter = Arc::clone(&subscriptions);
        let (publications_tx, publications) = mpsc::unbounded_channel();
        let task = tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = listener.accept().await else { break };
                let listeners = Arc::clone(&task_listeners);
                let subscriptions = Arc::clone(&subscription_counter);
                let publications = publications_tx.clone();
                tokio::spawn(async move {
                    let (mut reader, mut writer) = stream.into_split();
                    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
                    let subscribed = Arc::new(AtomicBool::new(false));
                    listeners.lock().unwrap().push(Listener {
                        tx: tx.clone(), subscribed: Arc::clone(&subscribed),
                    });
                    let writer_task = tokio::spawn(async move {
                        while let Some(packet) = rx.recv().await {
                            // An empty sentinel closes this local socket to test reconnect.
                            if packet.is_empty() || writer.write_all(&packet).await.is_err() { break; }
                        }
                    });
                    while let Ok((header, body)) = read_packet(&mut reader).await {
                        match header & 0xf0 {
                            0x10 => { let _ = tx.send(vec![0x20, 2, 0, 0]); }
                            0x80 if body.len() >= 2 => {
                                subscribed.store(true, Ordering::Release);
                                subscriptions.fetch_add(1, Ordering::AcqRel);
                                let _ = tx.send(vec![0x90, 3, body[0], body[1], 1]);
                            }
                            0x30 if body.len() >= 2 => {
                                let topic_len = u16::from_be_bytes([body[0], body[1]]) as usize;
                                let qos = (header >> 1) & 3;
                                let payload_start = 2 + topic_len + if qos > 0 { 2 } else { 0 };
                                if payload_start > body.len() { break; }
                                let topic = String::from_utf8_lossy(&body[2..2 + topic_len]).to_string();
                                if qos == 1 {
                                    let _ = tx.send(vec![0x40, 2, body[2 + topic_len], body[3 + topic_len]]);
                                }
                                let payload = body[payload_start..].to_vec();
                                let _ = publications.send((topic.clone(), payload.clone()));
                                let packet = publish_qos0(&topic, &payload);
                                let targets: Vec<_> = listeners.lock().unwrap().iter()
                                    .filter(|entry| entry.subscribed.load(Ordering::Acquire))
                                    .map(|entry| entry.tx.clone()).collect();
                                for target in targets { let _ = target.send(packet.clone()); }
                            }
                            0xc0 => { let _ = tx.send(vec![0xd0, 0]); }
                            0xe0 => break,
                            _ => {}
                        }
                    }
                    writer_task.abort();
                });
            }
        });
        Self { addr, publications, subscriptions, listeners, task }
    }

    fn disconnect_all(&self) {
        let mut listeners = self.listeners.lock().unwrap();
        for listener in listeners.drain(..) {
            listener.subscribed.store(false, Ordering::Release);
            let _ = listener.tx.send(Vec::new());
        }
    }

    async fn wait_for_subscriptions(&self, count: u64) {
        timeout(Duration::from_secs(10), async {
            while self.subscriptions.load(Ordering::Acquire) < count {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }).await.expect("local broker did not receive a subscription");
    }

    async fn next_publication(&mut self, topic: &str, payload: &[u8]) {
        timeout(Duration::from_secs(10), async {
            loop {
                let (actual_topic, actual_payload) = self.publications.recv().await.unwrap();
                if actual_topic == topic && actual_payload == payload { break; }
            }
        }).await.expect("publication did not reach the local broker");
    }

    async fn next_nonempty_publication(&mut self, topic: &str) -> Vec<u8> {
        timeout(Duration::from_secs(10), async {
            loop {
                let (actual_topic, payload) = self.publications.recv().await.unwrap();
                if actual_topic == topic && !payload.is_empty() { break payload; }
            }
        }).await.expect("presence did not reach the local broker")
    }
}

async fn read_packet(reader: &mut OwnedReadHalf) -> std::io::Result<(u8, Vec<u8>)> {
    let header = reader.read_u8().await?;
    let mut remaining = 0usize;
    let mut multiplier = 1usize;
    let mut complete = false;
    for _ in 0..4 {
        let byte = reader.read_u8().await?;
        remaining += (byte as usize & 0x7f) * multiplier;
        if byte & 0x80 == 0 { complete = true; break; }
        multiplier *= 128;
    }
    if !complete || remaining > MAX_MQTT_PACKET_BYTES {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad MQTT length"));
    }
    let mut body = vec![0; remaining];
    reader.read_exact(&mut body).await?;
    Ok((header, body))
}

fn publish_qos0(topic: &str, payload: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&(topic.len() as u16).to_be_bytes());
    body.extend_from_slice(topic.as_bytes());
    body.extend_from_slice(payload);
    let mut packet = vec![0x30];
    let mut length = body.len();
    loop {
        let mut byte = (length % 128) as u8;
        length /= 128;
        if length > 0 { byte |= 0x80; }
        packet.push(byte);
        if length == 0 { break; }
    }
    packet.extend(body);
    packet
}

fn local_transport(node: &str, primary: SocketAddr, secondary: SocketAddr) -> MqttTransport {
    fn make_client(node: &str, addr: SocketAddr) -> (AsyncClient, EventLoop) {
        let mut options = MqttOptions::new(node, addr.ip().to_string(), addr.port());
        options.set_keep_alive(Duration::from_secs(60));
        options.set_clean_session(true);
        options.set_max_packet_size(MAX_MQTT_PACKET_BYTES, MAX_MQTT_PACKET_BYTES);
        AsyncClient::new(options, MQTT_CLIENT_REQUEST_BUFFER)
    }
    let (client, eventloop) = make_client(&format!("{node}-primary"), primary);
    let (secondary_client, secondary_eventloop) = make_client(&format!("{node}-secondary"), secondary);
    let (event_tx, event_rx) = mpsc::channel(MQTT_EVENT_BUFFER);
    let shared_state = Arc::new(MqttSharedRuntimeState::new(MQTT_LOSS_INTOLERANT_INBOX_CAPACITY));
    MqttTransport {
        client,
        eventloop: Some(eventloop),
        event_tx: Some(event_tx),
        event_rx,
        shared_state,
        eventloop_task: None,
        eventloop_watchdog_task: None,
        liveness: Arc::new(MqttLivenessProbe::new()),
        shutdown_requested: Arc::new(AtomicBool::new(false)),
        notification_channel_closed: false,
        has_connected_once: false,
        primary_ready: Arc::new(AtomicBool::new(false)),
        secondary_client,
        secondary_eventloop: Some(secondary_eventloop),
        secondary_eventloop_task: None,
        secondary_liveness: Arc::new(MqttLivenessProbe::new()),
        secondary_ready: Arc::new(AtomicBool::new(false)),
        last_presence: StdMutex::new(None),
        broker_generation: broker_settings_generation(),
        ephemeral: false,
        node_id: node.to_string(),
        peers: Arc::new(Mutex::new(Vec::new())),
        relay_nodes: Arc::new(Mutex::new(Vec::new())),
    }
}

async fn wait_for_secondary(transport: &mut MqttTransport) {
    timeout(Duration::from_secs(10), async {
        while !transport.secondary_ready.load(Ordering::Acquire) {
            transport.poll_event().await;
        }
    }).await.expect("secondary subscription request was not queued");
}

async fn receive(transport: &mut MqttTransport, topic: &str, payload: &str) {
    timeout(Duration::from_secs(10), async {
        loop {
            if let Some(event) = transport.poll_event().await {
                if event.topic == topic && event.payload == payload { break; }
            }
        }
    }).await.expect("recipient did not receive the publication");
}

#[test]
fn cached_presence_never_makes_old_or_future_addresses_fresh() {
    let cached = CachedPresence {
        payload: "original|timestamp|with-address".into(),
        display_name: "Sender".into(),
        sent_at_ms: 1_000_000,
    };
    assert_eq!(cached.replay_at("pk_sender", 1_010_000).as_deref(), Some("original|timestamp|with-address"));
    for now in [1_200_001, 939_999] {
        let replay = cached.replay_at("pk_sender", now).unwrap();
        assert!(replay.starts_with("pk_sender|Sender||client|"));
        assert!(replay.ends_with(&format!("|{now}")));
    }
    assert_eq!(cached.replay_at("pk_sender", 0), None);
}

#[tokio::test]
async fn local_sessions_replay_current_presence_on_late_secondary_and_reconnect() {
    let mut wss_identity = TestBroker::start().await;
    let mut public_identity = TestBroker::start().await;
    // Local TCP sockets model the distinct broker identities; this test does
    // not exercise WSS, TLS, a real VPN or Android.
    let mut sender = local_transport("pk_sender", public_identity.addr, wss_identity.addr);
    sender.subscribe().await.unwrap();
    sender.publish_presence("Sender", Some("203.0.113.7:7777"), true).await.unwrap();
    wait_for_secondary(&mut sender).await;
    wss_identity.wait_for_subscriptions(1).await;

    let presence_topic = "p2pm2/presence/pk_sender";
    let original = public_identity.next_nonempty_publication(presence_topic).await;
    let replay = wss_identity.next_nonempty_publication(presence_topic).await;
    assert!(String::from_utf8_lossy(&original).starts_with("pk_sender|Sender|203.0.113.7:7777|relay|"));
    assert_eq!(replay, original, "replay must retain the original observation time");

    // Losing only the secondary must re-subscribe and replay the same fresh
    // observation, not invent a new timestamp for a possibly old address.
    wss_identity.disconnect_all();
    timeout(Duration::from_secs(10), async {
        while wss_identity.subscriptions.load(Ordering::Acquire) < 2 {
            sender.poll_event().await;
        }
    }).await.expect("secondary did not reconnect and subscribe");
    assert_eq!(wss_identity.next_nonempty_publication(presence_topic).await, original);

    // If the cached address has aged out, reconnect still announces that the
    // process is alive, but must not re-advertise that address as fresh.
    sender.last_presence.lock().unwrap().as_mut().unwrap().sent_at_ms =
        crate::storage::models::now_ms() - crate::network::presence_scope::LIVE_PRESENCE_TTL_MS - 1;
    wss_identity.disconnect_all();
    timeout(Duration::from_secs(10), async {
        while wss_identity.subscriptions.load(Ordering::Acquire) < 3 {
            sender.poll_event().await;
        }
    }).await.expect("secondary did not reconnect a second time");
    let addressless = wss_identity.next_nonempty_publication(presence_topic).await;
    assert!(String::from_utf8_lossy(&addressless).starts_with("pk_sender|Sender||client|"));

    public_identity.disconnect_all();
    timeout(Duration::from_secs(10), async {
        while public_identity.subscriptions.load(Ordering::Acquire) < 2 {
            sender.poll_event().await;
        }
    }).await.expect("primary did not reconnect and subscribe");
    let primary_replay = public_identity.next_nonempty_publication(presence_topic).await;
    assert!(String::from_utf8_lossy(&primary_replay).starts_with("pk_sender|Sender||client|"));

    let mut recipient = local_transport("pk_recipient", wss_identity.addr, wss_identity.addr);
    recipient.subscribe().await.unwrap();
    wait_for_secondary(&mut recipient).await;
    wss_identity.wait_for_subscriptions(5).await;

    // Only the sender's WSS-identity path remains active; it still reaches
    // the peer whose primary uses that same broker identity.
    sender.primary_ready.store(false, Ordering::Release);
    sender.send_mesh_relay("pk_recipient", "wire-one").await.unwrap();
    wss_identity.next_publication("p2pm2/msg/pk_recipient", b"wire-one").await;
    receive(&mut recipient, "p2pm2/msg/pk_recipient", "wire-one").await;
}

#[tokio::test]
async fn copies_arriving_from_two_independent_local_brokers_are_deduplicated() {
    let mut first = TestBroker::start().await;
    let mut second = TestBroker::start().await;
    let mut sender = local_transport("pk_sender", first.addr, second.addr);
    let mut recipient = local_transport("pk_recipient", first.addr, second.addr);
    sender.subscribe().await.unwrap();
    wait_for_secondary(&mut sender).await;
    recipient.subscribe().await.unwrap();
    wait_for_secondary(&mut recipient).await;
    first.wait_for_subscriptions(2).await;
    second.wait_for_subscriptions(2).await;

    let topic = "p2pm2/msg/pk_recipient";
    let before = recipient.shared_state.duplicate_drops.load(Ordering::Relaxed);
    sender.send_mesh_relay("pk_recipient", "wire-two").await.unwrap();
    first.next_publication(topic, b"wire-two").await;
    second.next_publication(topic, b"wire-two").await;
    receive(&mut recipient, topic, "wire-two").await;
    timeout(Duration::from_secs(10), async {
        while recipient.shared_state.duplicate_drops.load(Ordering::Relaxed) <= before {
            recipient.poll_event().await;
        }
    }).await.expect("second broker copy was not deduplicated");
    assert!(recipient.shared_state.loss_intolerant_inbox.pop().is_none());
}
