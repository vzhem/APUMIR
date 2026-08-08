//! Storage FFI — публичный API хранилища для Kotlin

use crate::storage::db::Database;
use crate::storage::models::{now_ms, Chat, Message, MessageContent, MessageStatus, User};
use std::sync::Mutex;

// ============================================================
// FFI Result
// ============================================================

/// Результат операции с хранилищем
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StorageResult {
    Ok,
    NotFound,
    Error(String),
}

impl StorageResult {
    pub fn is_ok(&self) -> bool {
        matches!(self, StorageResult::Ok)
    }

    pub fn unwrap(self) {
        match self {
            StorageResult::Ok => {}
            StorageResult::NotFound => panic!("StorageResult::NotFound"),
            StorageResult::Error(e) => panic!("StorageResult::Error: {}", e),
        }
    }
}

// ============================================================
// Storage Manager FFI
// ============================================================

/// Менеджер хранилища — единственная точка входа из Kotlin
pub struct StorageManagerFfi {
    db: Mutex<Option<Database>>,
}

impl StorageManagerFfi {
    pub fn new() -> Self {
        Self {
            db: Mutex::new(None),
        }
    }

    /// Инициализировать хранилище в памяти (тесты)
    pub fn init_in_memory(&self) -> StorageResult {
        match Database::open_in_memory() {
            Ok(db) => {
                *self.db.lock().unwrap() = Some(db);
                StorageResult::Ok
            }
            Err(e) => StorageResult::Error(e.to_string()),
        }
    }

    /// Инициализировать хранилище по пути
    pub fn init(&self, path: &str) -> StorageResult {
        match Database::open(path) {
            Ok(db) => {
                *self.db.lock().unwrap() = Some(db);
                StorageResult::Ok
            }
            Err(e) => StorageResult::Error(e.to_string()),
        }
    }

    /// Готово ли хранилище?
    pub fn is_ready(&self) -> bool {
        self.db.lock().unwrap().is_some()
    }

    // --- Users ---

    pub fn save_user(&self, id: String, display_name: String, is_self: bool) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => {
                let mut user = User::new(id, display_name);
                user.is_self = is_self;
                match db.insert_user(&user) {
                    Ok(_) => StorageResult::Ok,
                    Err(e) => StorageResult::Error(e.to_string()),
                }
            }
        }
    }

    pub fn get_user(&self, id: &str) -> Option<User> {
        let guard = self.db.lock().unwrap();
        guard.as_ref()?.get_user(id).ok()?
    }

    pub fn get_all_users(&self) -> Vec<User> {
        let guard = self.db.lock().unwrap();
        guard
            .as_ref()
            .and_then(|db| db.get_all_users().ok())
            .unwrap_or_default()
    }

    // --- Chats ---

    pub fn create_direct_chat(&self, chat_id: String) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => {
                let chat = Chat::new_direct(chat_id);
                match db.insert_chat(&chat) {
                    Ok(_) => StorageResult::Ok,
                    Err(e) => StorageResult::Error(e.to_string()),
                }
            }
        }
    }

    pub fn create_group_chat(&self, chat_id: String, title: String) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => {
                let chat = Chat::new_group(chat_id, title);
                match db.insert_chat(&chat) {
                    Ok(_) => StorageResult::Ok,
                    Err(e) => StorageResult::Error(e.to_string()),
                }
            }
        }
    }

    pub fn get_chat(&self, chat_id: &str) -> Option<Chat> {
        let guard = self.db.lock().unwrap();
        guard.as_ref()?.get_chat(chat_id).ok()?
    }

    pub fn get_all_chats(&self) -> Vec<Chat> {
        let guard = self.db.lock().unwrap();
        guard
            .as_ref()
            .and_then(|db| db.get_all_chats().ok())
            .unwrap_or_default()
    }

    pub fn mark_chat_read(&self, chat_id: &str) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => match db.reset_unread(chat_id) {
                Ok(_) => StorageResult::Ok,
                Err(e) => StorageResult::Error(e.to_string()),
            },
        }
    }

    // --- Messages ---

    pub fn save_message(
        &self,
        id: String,
        chat_id: String,
        sender_id: String,
        text: String,
    ) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => {
                let msg = Message::new(
                    id.clone(),
                    chat_id.clone(),
                    sender_id,
                    MessageContent::Text(text),
                );
                match db.insert_message(&msg) {
                    Ok(_) => {
                        // Обновляем last_message в чате
                        let _ = db.update_chat_last_message(&chat_id, &id, now_ms());
                        let _ = db.increment_unread(&chat_id);
                        StorageResult::Ok
                    }
                    Err(e) => StorageResult::Error(e.to_string()),
                }
            }
        }
    }

    pub fn get_messages(&self, chat_id: &str, limit: usize) -> Vec<Message> {
        let guard = self.db.lock().unwrap();
        guard
            .as_ref()
            .and_then(|db| db.get_messages(chat_id, limit, None).ok())
            .unwrap_or_default()
    }

    pub fn update_message_status(&self, message_id: &str, status: MessageStatus) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => match db.update_message_status(message_id, status) {
                Ok(_) => StorageResult::Ok,
                Err(e) => StorageResult::Error(e.to_string()),
            },
        }
    }

    pub fn delete_message(&self, message_id: &str) -> StorageResult {
        let guard = self.db.lock().unwrap();
        match guard.as_ref() {
            None => StorageResult::Error("DB not initialized".into()),
            Some(db) => match db.soft_delete_message(message_id, now_ms()) {
                Ok(_) => StorageResult::Ok,
                Err(e) => StorageResult::Error(e.to_string()),
            },
        }
    }

    pub fn message_count(&self, chat_id: &str) -> i64 {
        let guard = self.db.lock().unwrap();
        guard
            .as_ref()
            .and_then(|db| db.count_messages(chat_id).ok())
            .unwrap_or(0)
    }
}

impl Default for StorageManagerFfi {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_storage() -> StorageManagerFfi {
        let s = StorageManagerFfi::new();
        s.init_in_memory();
        s
    }

    // --- Init ---

    #[test]
    fn test_init_in_memory() {
        let s = StorageManagerFfi::new();
        assert!(!s.is_ready());
        assert_eq!(s.init_in_memory(), StorageResult::Ok);
        assert!(s.is_ready());
    }

    #[test]
    fn test_not_initialized_returns_error() {
        let s = StorageManagerFfi::new();
        let r = s.save_user("u1".into(), "Alice".into(), false);
        assert_eq!(r, StorageResult::Error("DB not initialized".into()));
    }

    // --- Users ---

    #[test]
    fn test_save_and_get_user() {
        let s = make_storage();
        s.save_user("u1".into(), "Alice".into(), false).unwrap();
        let user = s.get_user("u1").unwrap();
        assert_eq!(user.display_name, "Alice");
    }

    #[test]
    fn test_save_self_user() {
        let s = make_storage();
        s.save_user("me".into(), "Me".into(), true).unwrap();
        let user = s.get_user("me").unwrap();
        assert!(user.is_self);
    }

    #[test]
    fn test_get_user_not_found() {
        let s = make_storage();
        assert!(s.get_user("nobody").is_none());
    }

    #[test]
    fn test_get_all_users() {
        let s = make_storage();
        s.save_user("u1".into(), "Alice".into(), false).unwrap();
        s.save_user("u2".into(), "Bob".into(), false).unwrap();
        assert_eq!(s.get_all_users().len(), 2);
    }

    // --- Chats ---

    #[test]
    fn test_create_direct_chat() {
        let s = make_storage();
        assert_eq!(s.create_direct_chat("c1".into()), StorageResult::Ok);
        assert!(s.get_chat("c1").is_some());
    }

    #[test]
    fn test_create_group_chat() {
        let s = make_storage();
        assert_eq!(
            s.create_group_chat("g1".into(), "My Group".into()),
            StorageResult::Ok
        );
        let chat = s.get_chat("g1").unwrap();
        assert_eq!(chat.title, Some("My Group".into()));
    }

    #[test]
    fn test_get_all_chats() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.create_direct_chat("c2".into()).unwrap();
        assert_eq!(s.get_all_chats().len(), 2);
    }

    #[test]
    fn test_mark_chat_read() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "hi".into())
            .unwrap();
        let r = s.mark_chat_read("c1");
        assert_eq!(r, StorageResult::Ok);
        let chat = s.get_chat("c1").unwrap();
        assert_eq!(chat.unread_count, 0);
    }

    // --- Messages ---

    #[test]
    fn test_save_and_get_message() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "Hello!".into())
            .unwrap();
        let msgs = s.get_messages("c1", 10);
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].content.as_text(), Some("Hello!"));
    }

    #[test]
    fn test_message_count() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "a".into())
            .unwrap();
        s.save_message("m2".into(), "c1".into(), "u1".into(), "b".into())
            .unwrap();
        assert_eq!(s.message_count("c1"), 2);
    }

    #[test]
    fn test_update_message_status() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "hi".into())
            .unwrap();
        let r = s.update_message_status("m1", MessageStatus::Read);
        assert_eq!(r, StorageResult::Ok);
    }

    #[test]
    fn test_delete_message() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "bye".into())
            .unwrap();
        let r = s.delete_message("m1");
        assert_eq!(r, StorageResult::Ok);
    }

    #[test]
    fn test_save_message_updates_chat() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        s.save_message("m1".into(), "c1".into(), "u1".into(), "hello".into())
            .unwrap();
        let chat = s.get_chat("c1").unwrap();
        assert_eq!(chat.last_message_id, Some("m1".into()));
        assert_eq!(chat.unread_count, 1);
    }

    #[test]
    fn test_get_messages_empty_chat() {
        let s = make_storage();
        s.create_direct_chat("c1".into()).unwrap();
        assert_eq!(s.get_messages("c1", 10).len(), 0);
    }
}
