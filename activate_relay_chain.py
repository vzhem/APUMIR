# -*- coding: utf-8 -*-
import os
import re

BASE = r"C:\APUMIR\p2p-messenger"
RUST_SRC = os.path.join(BASE, "rust-core", "src")

print("=" * 70)
print("1. MQTT обработчик: активация ретрансляции")
print("=" * 70)
CORE_FILE = os.path.join(RUST_SRC, "engine", "core.rs")

with open(CORE_FILE, "r", encoding="utf-8") as f:
    core = f.read()

# 1.1. Исправить MQTT обработчик — вместо continue → enqueue
old_mqtt_handler = '''                } else if evt.topic.starts_with("p2pm2/msg/") {
                    // Формат: senderId|messageId|chatId|recipientId|text
                    let parts: Vec<&str> = evt.payload.splitn(5, '|').collect();
                    if parts.len() == 5 && parts[0] != node_id {  // Skip own messages
                        let sender_id = parts[0];
                        let message_id = parts[1];
                        let chat_id = parts[2];
                        let recipient_id = parts[3];
                        let text = parts[4];

                        // ФИЛЬТРАЦИЯ: проверяем что сообщение адресовано нам
                        if recipient_id != node_id {
                            tracing::debug!("MQTT: message for {} (not me {}) — relay only", recipient_id, node_id);
                            // Не emit событие, просто пропускаем (relay only)
                            continue;
                        }

                        tracing::info!("MQTT: message from {} to {}", sender_id, recipient_id);
                        let ts = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis() as i64;
                        events.emit(CoreEvent::MessageReceived {
                            message_id: message_id.to_string(),
                            chat_id: chat_id.to_string(),
                            sender_id: sender_id.to_string(),
                            text: text.to_string(),
                            timestamp: ts,
                        });
                        tracing::info!("MQTT: MessageReceived EMITTED to EventBus");
                    }
                }'''

new_mqtt_handler = '''                } else if evt.topic.starts_with("p2pm2/msg/") {
                    // Формат: senderId|messageId|chatId|recipientId|text
                    let parts: Vec<&str> = evt.payload.splitn(5, '|').collect();
                    if parts.len() == 5 && parts[0] != node_id {  // Skip own messages
                        let sender_id = parts[0];
                        let message_id = parts[1];
                        let chat_id = parts[2];
                        let recipient_id = parts[3];
                        let text = parts[4];

                        // ФИЛЬТРАЦИЯ: проверяем что сообщение адресовано нам
                        if recipient_id != node_id {
                            tracing::debug!("MQTT: message for {} (not me {}) — RELAY: queue for offline delivery", recipient_id, node_id);
                            
                            // РЕТРАНСЛЯЦИЯ: сохранить в очередь для оффлайн доставки
                            if let Some(ref queue_arc) = queue2 {
                                use sha2::{Sha256, Digest};
                                use crate::network::message_queue::QueuedMessage;
                                
                                // Hash recipient_id → [u8; 32]
                                let mut rh = Sha256::new();
                                rh.update(recipient_id.as_bytes());
                                let rid_hash = rh.finalize();
                                let mut rid = [0u8; 32];
                                rid.copy_from_slice(&rid_hash);
                                
                                // Hash message_id → [u8; 16]
                                let mut mh = Sha256::new();
                                mh.update(message_id.as_bytes());
                                let mhash = mh.finalize();
                                let mut mid = [0u8; 16];
                                mid.copy_from_slice(&mhash[..16]);
                                
                                // Сохранить ВЕСЬ payload для ретрансляции
                                let payload = evt.payload.clone();
                                let qmsg = QueuedMessage::new(mid, rid, payload.as_bytes().to_vec());
                                
                                if let Ok(_) = queue_arc.enqueue(qmsg).await {
                                    tracing::info!("MQTT: queued for relay to {} (message_id={})", recipient_id, &message_id[..8.min(message_id.len())]);
                                } else {
                                    tracing::warn!("MQTT: failed to queue message for {}", recipient_id);
                                }
                            }
                            continue;
                        }

                        tracing::info!("MQTT: message from {} to ME ({})", sender_id, recipient_id);
                        let ts = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis() as i64;
                        events.emit(CoreEvent::MessageReceived {
                            message_id: message_id.to_string(),
                            chat_id: chat_id.to_string(),
                            sender_id: sender_id.to_string(),
                            text: text.to_string(),
                            timestamp: ts,
                        });
                        tracing::info!("MQTT: MessageReceived EMITTED to EventBus");
                        
                        // ШЛЁМ ACK: сообщение доставлено → все ретрансляторы удалят из очереди
                        if let Some(ref transport_arc) = transport2 {
                            let ack_payload = format!("ack|{}", message_id);
                            let _ = transport_arc.publish_ack(&ack_payload).await;
                            tracing::debug!("MQTT: sent ACK for message_id={}", &message_id[..8.min(message_id.len())]);
                        }
                    }
                }'''

if old_mqtt_handler in core:
    core = core.replace(old_mqtt_handler, new_mqtt_handler)
    print("✓ MQTT: активи