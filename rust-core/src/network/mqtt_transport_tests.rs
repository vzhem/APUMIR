use super::*;

#[test]
fn reconnect_backoff_grows_then_holds_at_the_cap() {
    // Тот же ряд, что был до правки (1, 2, 4, 8, 16, 30, 30…): правка
    // только собрала число в `network::timing`, поведение не изменилось.
    let mut secs = 1u64;
    let mut seen = vec![secs];
    for _ in 0..8 {
        secs = next_reconnect_backoff(secs);
        seen.push(secs);
    }
    assert_eq!(seen, vec![1, 2, 4, 8, 16, 30, 30, 30, 30]);
    // И ни одна пауза не выходит за потолок.
    assert!(seen.iter().all(|value| *value <= MQTT_RECONNECT_BACKOFF_MAX_SECS));
}

/// Наш предел обязан покрывать то, что очередь пересылки пообещала
/// хранить: иначе запись лежит в очереди и «ждёт получателя» вечно, потому
/// что транспорт физически не может её отдать.
#[test]
fn mqtt_packet_limit_covers_the_relay_queue_promise() {
    assert!(
        MAX_MQTT_PACKET_BYTES >= crate::network::relay_queue::MAX_MESH_RELAY_ENVELOPE_BYTES,
        "предел пакета {} меньше того, что принимает очередь ({})",
        MAX_MQTT_PACKET_BYTES,
        crate::network::relay_queue::MAX_MESH_RELAY_ENVELOPE_BYTES
    );
}

/// Умолчание rumqttc (10 240 байт) не наш случай, и это не мелочь: наш
/// САМЫЙ БОЛЬШОЙ законный пакет (кусок истории 16 КиБ) больше него после
/// двух base64 — именно поэтому истории «ждали получателя», а отчёт это
/// показывал как отказ брокера.
#[test]
fn largest_story_packet_fits_our_limit_but_not_the_client_default() {
    // Кусок истории: JSON до 16 КиБ (StoryStore.encodePackets).
    let story_packet = 16 * 1024usize;
    // SealedWire: base64 шифротекста (тело + 16 байт тега GCM) плюс
    // base64 конверта ключа (203 + подпись 200 байт с запасом) и IV.
    let ciphertext_b64 = (story_packet + 16).div_ceil(3) * 4;
    let sealed_wire = 10 + (700usize).div_ceil(3) * 4 + 16 + ciphertext_b64 + 2;
    // Relay-конверт: ещё один base64 поверх всей запечатанной строки плюс
    // служебные поля (msg_id, получатель, источник, область, ttl, hop).
    let relay_payload = (sealed_wire + 2).div_ceil(3) * 4 + 210;
    let packet = publish_packet_size("p2pm2/msg/pk_0123456789abcdef0123456789abcdef", relay_payload);

    assert!(
        packet > 10 * 1024,
        "пакет обязан быть больше умолчания rumqttc, иначе проверять нечего: {packet}"
    );
    assert!(
        packet <= MAX_MQTT_PACKET_BYTES,
        "самый большой наш пакет {packet} не влезает в предел {}",
        MAX_MQTT_PACKET_BYTES
    );
}

/// Размер пакета считается по всему пакету (заголовок, тема, тело) — так же,
/// как его считает rumqttc, иначе проверка предела разошлась бы с клиентом.
#[test]
fn publish_packet_size_counts_header_topic_and_payload() {
    assert_eq!(publish_packet_size("", 0), 4); // 1 + 1 + (2 + 0 + 0)
    assert_eq!(publish_packet_size("ab", 3), 9); // 1 + 1 + (2 + 2 + 3)
    // 128 байт remaining: длина занимает уже два байта.
    assert_eq!(publish_packet_size("", 126), 131);
}

/// Предел проверяется ДО сети: и на границе, и за ней.
#[test]
fn our_limit_refuses_oversize_publish_before_the_network() {
    let topic = "p2pm2/msg/pk_0123456789abcdef0123456789abcdef";
    // Мелкий пакет и пакет с килобайтным запасом проходят.
    assert!(publish_fits_our_limit("relay publish", topic, 1024));
    assert!(publish_fits_our_limit(
        "relay publish",
        topic,
        MAX_MQTT_PACKET_BYTES - 1024
    ));
    // А ровно предел в ТЕЛЕ — уже нет: к телу добавляются тема и заголовок,
    // именно на этом и разошлась бы проверка «по длине тела».
    assert!(!publish_fits_our_limit(
        "relay publish",
        topic,
        MAX_MQTT_PACKET_BYTES
    ));
}

#[test]
fn r4_broker_set_is_exactly_hivemq_then_emqx() {
    assert_eq!(
        MQTT_BROKERS,
        &[("broker.hivemq.com", 1883), ("broker.emqx.io", 1883)]
    );
}

#[test]
fn receipt_topic_is_unique_and_does_not_embed_message_id() {
    let first = mesh_receipt_topic("pk_origin", "message/with+#wildcards").unwrap();
    let same = mesh_receipt_topic("pk_origin", "message/with+#wildcards").unwrap();
    let second = mesh_receipt_topic("pk_origin", "another-message").unwrap();

    assert_eq!(first, same);
    assert_ne!(first, second);
    assert!(first.starts_with("p2pm2/msg/pk_origin/receipt/"));
    assert!(!first.contains("message/with+#wildcards"));
    assert_eq!(first.rsplit('/').next().unwrap().len(), 64);
}

#[test]
fn cleanup_receipt_topic_is_shared_non_origin_path() {
    let topic = mesh_cleanup_receipt_topic("message/with+#wildcards").unwrap();
    assert!(topic.starts_with("p2pm2/receipt/cleanup/"));
    assert_eq!(topic, mesh_cleanup_receipt_topic("message/with+#wildcards").unwrap());
    assert!(!topic.contains("message/with+#wildcards"));
    assert!(mesh_cleanup_receipt_topic("").is_err());
    assert!(mesh_cleanup_receipt_topic(&"m".repeat(257)).is_err());
}

#[test]
fn receipt_topic_rejects_unsafe_origin_and_unbounded_message_id() {
    assert!(mesh_receipt_topic("pk_origin/other", "m1").is_err());
    assert!(mesh_receipt_topic("pk_origin+", "m1").is_err());
    assert!(mesh_receipt_topic("pk_origin", "").is_err());
    assert!(mesh_receipt_topic("pk_origin", &"m".repeat(257)).is_err());
}
