//! Database — инициализация SQLite и миграции
//! Append-Only Log для сообщений

use rusqlite::{params, Connection, Result as SqlResult};
use std::path::Path;

use super::models::{
    Chat, ChatKind, ChatMember, MemberRole, Message, MessageContent, MessageStatus, User,
};

// ============================================================
// Migration SQL
// ============================================================

const MIGRATION_V1: &str = "
-- Пользователи и контакты
CREATE TABLE IF NOT EXISTS users (
    id           TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    avatar       TEXT,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    is_self      INTEGER NOT NULL DEFAULT 0
);

-- Чаты (диалоги и группы)
CREATE TABLE IF NOT EXISTS chats (
    id              TEXT PRIMARY KEY,
    kind            TEXT NOT NULL DEFAULT 'direct',
    title           TEXT,
    created_at      INTEGER NOT NULL,
    last_message_at INTEGER NOT NULL,
    last_message_id TEXT,
    unread_count    INTEGER NOT NULL DEFAULT 0
);

-- Участники чатов
CREATE TABLE IF NOT EXISTS chat_members (
    chat_id   TEXT NOT NULL,
    user_id   TEXT NOT NULL,
    role      TEXT NOT NULL DEFAULT 'member',
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (chat_id, user_id),
    FOREIGN KEY (chat_id) REFERENCES chats(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Сообщения (Append-Only Log)
CREATE TABLE IF NOT EXISTS messages (
    id           TEXT PRIMARY KEY,
    chat_id      TEXT NOT NULL,
    sender_id    TEXT NOT NULL,
    content_kind TEXT NOT NULL,
    content_data TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',
    created_at   INTEGER NOT NULL,
    vector_clock TEXT NOT NULL DEFAULT '{}',
    reply_to     TEXT,
    is_deleted   INTEGER NOT NULL DEFAULT 0,
    deleted_at   INTEGER,
    FOREIGN KEY (chat_id) REFERENCES chats(id)
);

-- Индексы для быстрого поиска
CREATE INDEX IF NOT EXISTS idx_messages_chat_id
    ON messages(chat_id, created_at);

CREATE INDEX IF NOT EXISTS idx_messages_sender
    ON messages(sender_id);

CREATE INDEX IF NOT EXISTS idx_chat_members_user
    ON chat_members(user_id);

-- Версия схемы
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY
);

INSERT OR IGNORE INTO schema_version (version) VALUES (1);
";

// ============================================================
// Database
// ============================================================

/// Обёртка над SQLite соединением
pub struct Database {
    conn: Connection,
}

impl Database {
    /// Открыть БД по пути (создаёт файл если не существует)
    pub fn open<P: AsRef<Path>>(path: P) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
        let db = Self { conn };
        db.init()?;
        Ok(db)
    }

    /// Открыть БД в памяти (для тестов)
    pub fn open_in_memory() -> SqlResult<Self> {
        let conn = Connection::open_in_memory()?;
        let db = Self { conn };
        db.init()?;
        Ok(db)
    }

    /// Инициализация: WAL режим + миграции
    fn init(&self) -> SqlResult<()> {
        // WAL для лучшей производительности
        self.conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        self.conn.execute_batch("PRAGMA foreign_keys=ON;")?;
        // Применяем миграции
        self.conn.execute_batch(MIGRATION_V1)?;
        Ok(())
    }

    /// Текущая версия схемы
    pub fn schema_version(&self) -> SqlResult<i64> {
        self.conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get(0)
            })
    }

    // ============================================================
    // Users
    // ============================================================

    pub fn insert_user(&self, user: &User) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO users
             (id, display_name, avatar, created_at, updated_at, is_self)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                user.id,
                user.display_name,
                user.avatar,
                user.created_at,
                user.updated_at,
                user.is_self as i32,
            ],
        )?;
        Ok(())
    }

    pub fn get_user(&self, id: &str) -> SqlResult<Option<User>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, display_name, avatar, created_at, updated_at, is_self
             FROM users WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![id])?;

        if let Some(row) = rows.next()? {
            Ok(Some(User {
                id: row.get(0)?,
                display_name: row.get(1)?,
                avatar: row.get(2)?,
                created_at: row.get(3)?,
                updated_at: row.get(4)?,
                is_self: row.get::<_, i32>(5)? != 0,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn get_all_users(&self) -> SqlResult<Vec<User>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, display_name, avatar, created_at, updated_at, is_self
             FROM users ORDER BY display_name",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(User {
                id: row.get(0)?,
                display_name: row.get(1)?,
                avatar: row.get(2)?,
                created_at: row.get(3)?,
                updated_at: row.get(4)?,
                is_self: row.get::<_, i32>(5)? != 0,
            })
        })?;

        rows.collect()
    }

    pub fn delete_user(&self, id: &str) -> SqlResult<usize> {
        self.conn
            .execute("DELETE FROM users WHERE id = ?1", params![id])
    }

    // ============================================================
    // Chats
    // ============================================================

    pub fn insert_chat(&self, chat: &Chat) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO chats
             (id, kind, title, created_at, last_message_at, last_message_id, unread_count)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                chat.id,
                chat.kind.as_str(),
                chat.title,
                chat.created_at,
                chat.last_message_at,
                chat.last_message_id,
                chat.unread_count,
            ],
        )?;
        Ok(())
    }

    pub fn get_chat(&self, id: &str) -> SqlResult<Option<Chat>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, kind, title, created_at, last_message_at,
                    last_message_id, unread_count
             FROM chats WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![id])?;

        if let Some(row) = rows.next()? {
            Ok(Some(Chat {
                id: row.get(0)?,
                kind: ChatKind::from_db_str(&row.get::<_, String>(1)?),
                title: row.get(2)?,
                created_at: row.get(3)?,
                last_message_at: row.get(4)?,
                last_message_id: row.get(5)?,
                unread_count: row.get(6)?,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn get_all_chats(&self) -> SqlResult<Vec<Chat>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, kind, title, created_at, last_message_at,
                    last_message_id, unread_count
             FROM chats ORDER BY last_message_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(Chat {
                id: row.get(0)?,
                kind: ChatKind::from_db_str(&row.get::<_, String>(1)?),
                title: row.get(2)?,
                created_at: row.get(3)?,
                last_message_at: row.get(4)?,
                last_message_id: row.get(5)?,
                unread_count: row.get(6)?,
            })
        })?;

        rows.collect()
    }

    pub fn update_chat_last_message(
        &self,
        chat_id: &str,
        message_id: &str,
        timestamp: i64,
    ) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE chats SET last_message_id = ?1,
             last_message_at = ?2 WHERE id = ?3",
            params![message_id, timestamp, chat_id],
        )?;
        Ok(())
    }

    pub fn increment_unread(&self, chat_id: &str) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE chats SET unread_count = unread_count + 1 WHERE id = ?1",
            params![chat_id],
        )?;
        Ok(())
    }

    pub fn reset_unread(&self, chat_id: &str) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE chats SET unread_count = 0 WHERE id = ?1",
            params![chat_id],
        )?;
        Ok(())
    }

    // ============================================================
    // Chat Members
    // ============================================================

    pub fn insert_member(&self, member: &ChatMember) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO chat_members
             (chat_id, user_id, role, joined_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![
                member.chat_id,
                member.user_id,
                member.role.as_str(),
                member.joined_at,
            ],
        )?;
        Ok(())
    }

    pub fn get_members(&self, chat_id: &str) -> SqlResult<Vec<ChatMember>> {
        let mut stmt = self.conn.prepare(
            "SELECT chat_id, user_id, role, joined_at
             FROM chat_members WHERE chat_id = ?1",
        )?;

        let rows = stmt.query_map(params![chat_id], |row| {
            Ok(ChatMember {
                chat_id: row.get(0)?,
                user_id: row.get(1)?,
                role: MemberRole::from_db_str(&row.get::<_, String>(2)?),
                joined_at: row.get(3)?,
            })
        })?;

        rows.collect()
    }

    pub fn remove_member(&self, chat_id: &str, user_id: &str) -> SqlResult<usize> {
        self.conn.execute(
            "DELETE FROM chat_members WHERE chat_id = ?1 AND user_id = ?2",
            params![chat_id, user_id],
        )
    }

    // ============================================================
    // Messages (Append-Only Log)
    // ============================================================

    pub fn insert_message(&self, msg: &Message) -> SqlResult<()> {
        let (content_kind, content_data) = serialize_content(&msg.content);

        self.conn.execute(
            "INSERT OR IGNORE INTO messages
             (id, chat_id, sender_id, content_kind, content_data,
              status, created_at, vector_clock, reply_to, is_deleted, deleted_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![
                msg.id,
                msg.chat_id,
                msg.sender_id,
                content_kind,
                content_data,
                msg.status.as_str(),
                msg.created_at,
                msg.vector_clock,
                msg.reply_to,
                msg.is_deleted as i32,
                msg.deleted_at,
            ],
        )?;
        Ok(())
    }

    pub fn get_message(&self, id: &str) -> SqlResult<Option<Message>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, chat_id, sender_id, content_kind, content_data,
                    status, created_at, vector_clock, reply_to, is_deleted, deleted_at
             FROM messages WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![id])?;

        if let Some(row) = rows.next()? {
            Ok(Some(row_to_message(row)?))
        } else {
            Ok(None)
        }
    }

    /// Получить сообщения чата (Append-Only — только чтение по порядку)
    pub fn get_messages(
        &self,
        chat_id: &str,
        limit: usize,
        before_ms: Option<i64>,
    ) -> SqlResult<Vec<Message>> {
        let before = before_ms.unwrap_or(i64::MAX);

        let mut stmt = self.conn.prepare(
            "SELECT id, chat_id, sender_id, content_kind, content_data,
                    status, created_at, vector_clock, reply_to, is_deleted, deleted_at
             FROM messages
             WHERE chat_id = ?1 AND created_at < ?2
             ORDER BY created_at DESC
             LIMIT ?3",
        )?;

        let rows = stmt.query_map(params![chat_id, before, limit as i64], |row| {
            row_to_message(row)
        })?;

        let mut messages: Vec<Message> = rows.collect::<SqlResult<_>>()?;
        messages.reverse(); // Хронологический порядок
        Ok(messages)
    }

    pub fn update_message_status(&self, id: &str, status: MessageStatus) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE messages SET status = ?1 WHERE id = ?2",
            params![status.as_str(), id],
        )?;
        Ok(())
    }

    pub fn soft_delete_message(&self, id: &str, deleted_at: i64) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE messages SET is_deleted = 1, deleted_at = ?1 WHERE id = ?2",
            params![deleted_at, id],
        )?;
        Ok(())
    }

    pub fn count_messages(&self, chat_id: &str) -> SqlResult<i64> {
        self.conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ?1",
            params![chat_id],
            |row| row.get(0),
        )
    }
}

// ============================================================
// Helpers
// ============================================================

fn serialize_content(content: &MessageContent) -> (String, String) {
    match content {
        MessageContent::Text(t) => ("text".into(), t.clone()),
        MessageContent::System(s) => ("system".into(), s.clone()),
        MessageContent::File { name, size, hash } => {
            ("file".into(), format!("{}|{}|{}", name, size, hash))
        }
    }
}

fn deserialize_content(kind: &str, data: &str) -> MessageContent {
    match kind {
        "text" => MessageContent::Text(data.to_string()),
        "system" => MessageContent::System(data.to_string()),
        "file" => {
            let parts: Vec<&str> = data.splitn(3, '|').collect();
            if parts.len() == 3 {
                MessageContent::File {
                    name: parts[0].to_string(),
                    size: parts[1].parse().unwrap_or(0),
                    hash: parts[2].to_string(),
                }
            } else {
                MessageContent::Text(data.to_string())
            }
        }
        _ => MessageContent::Text(data.to_string()),
    }
}

fn row_to_message(row: &rusqlite::Row) -> SqlResult<Message> {
    let content_kind: String = row.get(3)?;
    let content_data: String = row.get(4)?;
    let status_str: String = row.get(5)?;

    Ok(Message {
        id: row.get(0)?,
        chat_id: row.get(1)?,
        sender_id: row.get(2)?,
        content: deserialize_content(&content_kind, &content_data),
        status: MessageStatus::from_db_str(&status_str),
        created_at: row.get(6)?,
        vector_clock: row.get(7)?,
        reply_to: row.get(8)?,
        is_deleted: row.get::<_, i32>(9)? != 0,
        deleted_at: row.get(10)?,
    })
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::models::{
        Chat, ChatMember, MemberRole, Message, MessageContent, MessageStatus, User,
    };

    fn make_db() -> Database {
        Database::open_in_memory().unwrap()
    }

    fn make_user(id: &str, name: &str) -> User {
        User::new(id.into(), name.into())
    }

    fn make_chat(id: &str) -> Chat {
        Chat::new_direct(id.into())
    }

    fn make_message(id: &str, chat_id: &str, sender: &str, text: &str) -> Message {
        Message::new(
            id.into(),
            chat_id.into(),
            sender.into(),
            MessageContent::Text(text.into()),
        )
    }

    // --- Schema ---

    #[test]
    fn test_schema_version_is_1() {
        let db = make_db();
        assert_eq!(db.schema_version().unwrap(), 1);
    }

    // --- Users ---

    #[test]
    fn test_insert_and_get_user() {
        let db = make_db();
        let user = make_user("u1", "Alice");
        db.insert_user(&user).unwrap();
        let fetched = db.get_user("u1").unwrap().unwrap();
        assert_eq!(fetched.id, "u1");
        assert_eq!(fetched.display_name, "Alice");
    }

    #[test]
    fn test_get_user_not_found() {
        let db = make_db();
        assert!(db.get_user("nonexistent").unwrap().is_none());
    }

    #[test]
    fn test_get_all_users() {
        let db = make_db();
        db.insert_user(&make_user("u1", "Alice")).unwrap();
        db.insert_user(&make_user("u2", "Bob")).unwrap();
        let users = db.get_all_users().unwrap();
        assert_eq!(users.len(), 2);
    }

    #[test]
    fn test_delete_user() {
        let db = make_db();
        db.insert_user(&make_user("u1", "Alice")).unwrap();
        db.delete_user("u1").unwrap();
        assert!(db.get_user("u1").unwrap().is_none());
    }

    #[test]
    fn test_user_is_self_flag() {
        let db = make_db();
        let mut user = make_user("me", "Me");
        user.is_self = true;
        db.insert_user(&user).unwrap();
        let fetched = db.get_user("me").unwrap().unwrap();
        assert!(fetched.is_self);
    }

    // --- Chats ---

    #[test]
    fn test_insert_and_get_chat() {
        let db = make_db();
        let chat = make_chat("chat1");
        db.insert_chat(&chat).unwrap();
        let fetched = db.get_chat("chat1").unwrap().unwrap();
        assert_eq!(fetched.id, "chat1");
        assert_eq!(fetched.kind, ChatKind::Direct);
    }

    #[test]
    fn test_get_chat_not_found() {
        let db = make_db();
        assert!(db.get_chat("no_chat").unwrap().is_none());
    }

    #[test]
    fn test_get_all_chats_sorted_by_last_message() {
        let db = make_db();
        let mut c1 = make_chat("c1");
        c1.last_message_at = 1000;
        let mut c2 = make_chat("c2");
        c2.last_message_at = 2000;
        db.insert_chat(&c1).unwrap();
        db.insert_chat(&c2).unwrap();
        let chats = db.get_all_chats().unwrap();
        assert_eq!(chats[0].id, "c2"); // Новее — первый
    }

    #[test]
    fn test_update_chat_last_message() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        db.update_chat_last_message("c1", "msg99", 9999).unwrap();
        let chat = db.get_chat("c1").unwrap().unwrap();
        assert_eq!(chat.last_message_id, Some("msg99".into()));
        assert_eq!(chat.last_message_at, 9999);
    }

    #[test]
    fn test_unread_count() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        db.increment_unread("c1").unwrap();
        db.increment_unread("c1").unwrap();
        let chat = db.get_chat("c1").unwrap().unwrap();
        assert_eq!(chat.unread_count, 2);
        db.reset_unread("c1").unwrap();
        let chat2 = db.get_chat("c1").unwrap().unwrap();
        assert_eq!(chat2.unread_count, 0);
    }

    // --- Chat Members ---

    #[test]
    fn test_insert_and_get_members() {
        let db = make_db();
        db.insert_user(&make_user("u1", "Alice")).unwrap();
        db.insert_user(&make_user("u2", "Bob")).unwrap();
        db.insert_chat(&make_chat("c1")).unwrap();

        let m1 = ChatMember::new("c1".into(), "u1".into(), MemberRole::Owner);
        let m2 = ChatMember::new("c1".into(), "u2".into(), MemberRole::Member);
        db.insert_member(&m1).unwrap();
        db.insert_member(&m2).unwrap();

        let members = db.get_members("c1").unwrap();
        assert_eq!(members.len(), 2);
    }

    #[test]
    fn test_remove_member() {
        let db = make_db();
        db.insert_user(&make_user("u1", "Alice")).unwrap();
        db.insert_chat(&make_chat("c1")).unwrap();
        let m = ChatMember::new("c1".into(), "u1".into(), MemberRole::Member);
        db.insert_member(&m).unwrap();
        db.remove_member("c1", "u1").unwrap();
        let members = db.get_members("c1").unwrap();
        assert_eq!(members.len(), 0);
    }

    // --- Messages ---

    #[test]
    fn test_insert_and_get_message() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        let msg = make_message("m1", "c1", "u1", "Hello!");
        db.insert_message(&msg).unwrap();
        let fetched = db.get_message("m1").unwrap().unwrap();
        assert_eq!(fetched.id, "m1");
        assert_eq!(fetched.content.as_text(), Some("Hello!"));
    }

    #[test]
    fn test_get_message_not_found() {
        let db = make_db();
        assert!(db.get_message("no_msg").unwrap().is_none());
    }

    #[test]
    fn test_get_messages_ordered() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();

        let mut m1 = make_message("m1", "c1", "u1", "First");
        m1.created_at = 1000;
        let mut m2 = make_message("m2", "c1", "u1", "Second");
        m2.created_at = 2000;
        let mut m3 = make_message("m3", "c1", "u1", "Third");
        m3.created_at = 3000;

        db.insert_message(&m1).unwrap();
        db.insert_message(&m2).unwrap();
        db.insert_message(&m3).unwrap();

        let msgs = db.get_messages("c1", 10, None).unwrap();
        assert_eq!(msgs.len(), 3);
        assert_eq!(msgs[0].id, "m1");
        assert_eq!(msgs[2].id, "m3");
    }

    #[test]
    fn test_get_messages_with_limit() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        for i in 0..5 {
            let mut m = make_message(&format!("m{}", i), "c1", "u1", "text");
            m.created_at = i as i64 * 1000;
            db.insert_message(&m).unwrap();
        }
        let msgs = db.get_messages("c1", 3, None).unwrap();
        assert_eq!(msgs.len(), 3);
    }

    #[test]
    fn test_update_message_status() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        let msg = make_message("m1", "c1", "u1", "Hi");
        db.insert_message(&msg).unwrap();
        db.update_message_status("m1", MessageStatus::Delivered)
            .unwrap();
        let fetched = db.get_message("m1").unwrap().unwrap();
        assert_eq!(fetched.status, MessageStatus::Delivered);
    }

    #[test]
    fn test_soft_delete_message() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        let msg = make_message("m1", "c1", "u1", "bye");
        db.insert_message(&msg).unwrap();
        db.soft_delete_message("m1", 99999).unwrap();
        let fetched = db.get_message("m1").unwrap().unwrap();
        assert!(fetched.is_deleted);
        assert_eq!(fetched.deleted_at, Some(99999));
    }

    #[test]
    fn test_count_messages() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        for i in 0..4 {
            db.insert_message(&make_message(&format!("m{}", i), "c1", "u1", "x"))
                .unwrap();
        }
        assert_eq!(db.count_messages("c1").unwrap(), 4);
    }

    #[test]
    fn test_file_message_roundtrip() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        let msg = Message::new(
            "mf1".into(),
            "c1".into(),
            "u1".into(),
            MessageContent::File {
                name: "photo.jpg".into(),
                size: 2048,
                hash: "deadbeef".into(),
            },
        );
        db.insert_message(&msg).unwrap();
        let fetched = db.get_message("mf1").unwrap().unwrap();
        assert!(matches!(fetched.content, MessageContent::File { .. }));
    }

    #[test]
    fn test_append_only_duplicate_ignored() {
        let db = make_db();
        db.insert_chat(&make_chat("c1")).unwrap();
        let msg = make_message("m1", "c1", "u1", "original");
        db.insert_message(&msg).unwrap();
        // INSERT OR IGNORE — повторная вставка игнорируется
        let mut msg2 = make_message("m1", "c1", "u1", "duplicate");
        msg2.created_at = 99999;
        db.insert_message(&msg2).unwrap();
        let fetched = db.get_message("m1").unwrap().unwrap();
        assert_eq!(fetched.content.as_text(), Some("original"));
    }
}
