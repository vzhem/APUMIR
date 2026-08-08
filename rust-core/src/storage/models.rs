//! РњРѕРґРµР»Рё РґР°РЅРЅС‹С… РґР»СЏ SQLite С…СЂР°РЅРёР»РёС‰Р°

use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

// ============================================================
// Helpers
// ============================================================

/// РўРµРєСѓС‰РµРµ РІСЂРµРјСЏ РІ РјРёР»Р»РёСЃРµРєСѓРЅРґР°С… UTC
pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

// ============================================================
// User / Identity
// ============================================================

/// Р›РѕРєР°Р»СЊРЅС‹Р№ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ РёР»Рё РєРѕРЅС‚Р°РєС‚
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct User {
    /// РџСѓР±Р»РёС‡РЅС‹Р№ РєР»СЋС‡ Ed25519 (hex)
    pub id: String,
    /// РћС‚РѕР±СЂР°Р¶Р°РµРјРѕРµ РёРјСЏ
    pub display_name: String,
    /// РђРІР°С‚Р°СЂ (РѕРїС†РёРѕРЅР°Р»СЊРЅРѕ, base64)
    pub avatar: Option<String>,
    /// Р’СЂРµРјСЏ СЃРѕР·РґР°РЅРёСЏ (ms UTC)
    pub created_at: i64,
    /// РџРѕСЃР»РµРґРЅРµРµ РѕР±РЅРѕРІР»РµРЅРёРµ (ms UTC)
    pub updated_at: i64,
    /// Р­С‚Рѕ Р»РѕРєР°Р»СЊРЅС‹Р№ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ?
    pub is_self: bool,
}

impl User {
    pub fn new(id: String, display_name: String) -> Self {
        let now = now_ms();
        Self {
            id,
            display_name,
            avatar: None,
            created_at: now,
            updated_at: now,
            is_self: false,
        }
    }

    pub fn new_self(id: String, display_name: String) -> Self {
        let mut u = Self::new(id, display_name);
        u.is_self = true;
        u
    }
}

// ============================================================
// Chat
// ============================================================

/// РўРёРї С‡Р°С‚Р°
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChatKind {
    Direct,
    Group,
}

impl ChatKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            ChatKind::Direct => "direct",
            ChatKind::Group => "group",
        }
    }

    pub fn from_db_str(s: &str) -> Self {
        match s {
            "group" => ChatKind::Group,
            _ => ChatKind::Direct,
        }
    }
}

/// Р§Р°С‚ (РґРёР°Р»РѕРі РёР»Рё РіСЂСѓРїРїР°)
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Chat {
    /// РЈРЅРёРєР°Р»СЊРЅС‹Р№ ID С‡Р°С‚Р°
    pub id: String,
    /// РўРёРї С‡Р°С‚Р°
    pub kind: ChatKind,
    /// РќР°Р·РІР°РЅРёРµ (РґР»СЏ РіСЂСѓРїРї)
    pub title: Option<String>,
    /// Р’СЂРµРјСЏ СЃРѕР·РґР°РЅРёСЏ (ms UTC)
    pub created_at: i64,
    /// Р’СЂРµРјСЏ РїРѕСЃР»РµРґРЅРµРіРѕ СЃРѕРѕР±С‰РµРЅРёСЏ (ms UTC)
    pub last_message_at: i64,
    /// ID РїРѕСЃР»РµРґРЅРµРіРѕ СЃРѕРѕР±С‰РµРЅРёСЏ
    pub last_message_id: Option<String>,
    /// РљРѕР»РёС‡РµСЃС‚РІРѕ РЅРµРїСЂРѕС‡РёС‚Р°РЅРЅС‹С…
    pub unread_count: i64,
}

impl Chat {
    pub fn new_direct(id: String) -> Self {
        let now = now_ms();
        Self {
            id,
            kind: ChatKind::Direct,
            title: None,
            created_at: now,
            last_message_at: now,
            last_message_id: None,
            unread_count: 0,
        }
    }

    pub fn new_group(id: String, title: String) -> Self {
        let now = now_ms();
        Self {
            id,
            kind: ChatKind::Group,
            title: Some(title),
            created_at: now,
            last_message_at: now,
            last_message_id: None,
            unread_count: 0,
        }
    }
}

// ============================================================
// Message
// ============================================================

/// РЎС‚Р°С‚СѓСЃ РґРѕСЃС‚Р°РІРєРё СЃРѕРѕР±С‰РµРЅРёСЏ
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageStatus {
    /// РЎРѕР·РґР°РЅРѕ Р»РѕРєР°Р»СЊРЅРѕ, РµС‰С‘ РЅРµ РѕС‚РїСЂР°РІР»РµРЅРѕ
    Pending,
    /// РћС‚РїСЂР°РІР»РµРЅРѕ (РїРѕРґС‚РІРµСЂР¶РґРµРЅРёРµ РѕС‚ СЃРµС‚Рё)
    Sent,
    /// Р”РѕСЃС‚Р°РІР»РµРЅРѕ РїРѕР»СѓС‡Р°С‚РµР»СЋ
    Delivered,
    /// РџСЂРѕС‡РёС‚Р°РЅРѕ
    Read,
    /// РћС€РёР±РєР° РѕС‚РїСЂР°РІРєРё
    Failed,
}

impl MessageStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            MessageStatus::Pending => "pending",
            MessageStatus::Sent => "sent",
            MessageStatus::Delivered => "delivered",
            MessageStatus::Read => "read",
            MessageStatus::Failed => "failed",
        }
    }

    pub fn from_db_str(s: &str) -> Self {
        match s {
            "sent" => MessageStatus::Sent,
            "delivered" => MessageStatus::Delivered,
            "read" => MessageStatus::Read,
            "failed" => MessageStatus::Failed,
            _ => MessageStatus::Pending,
        }
    }
}

/// РўРёРї СЃРѕРґРµСЂР¶РёРјРѕРіРѕ СЃРѕРѕР±С‰РµРЅРёСЏ
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageContent {
    /// РўРµРєСЃС‚РѕРІРѕРµ СЃРѕРѕР±С‰РµРЅРёРµ
    Text(String),
    /// РЎРёСЃС‚РµРјРЅРѕРµ СЃРѕРѕР±С‰РµРЅРёРµ (РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ РґРѕР±Р°РІР»РµРЅ Рё С‚.Рґ.)
    System(String),
    /// Р¤Р°Р№Р» (РїСѓС‚СЊ РёР»Рё hash)
    File {
        name: String,
        size: u64,
        hash: String,
    },
}

impl MessageContent {
    pub fn kind(&self) -> &'static str {
        match self {
            MessageContent::Text(_) => "text",
            MessageContent::System(_) => "system",
            MessageContent::File { .. } => "file",
        }
    }

    pub fn as_text(&self) -> Option<&str> {
        match self {
            MessageContent::Text(t) => Some(t.as_str()),
            _ => None,
        }
    }
}

/// РЎРѕРѕР±С‰РµРЅРёРµ РІ С‡Р°С‚Рµ (Append-Only)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    /// РЈРЅРёРєР°Р»СЊРЅС‹Р№ ID СЃРѕРѕР±С‰РµРЅРёСЏ (UUID РёР»Рё hash)
    pub id: String,
    /// ID С‡Р°С‚Р°
    pub chat_id: String,
    /// ID РѕС‚РїСЂР°РІРёС‚РµР»СЏ
    pub sender_id: String,
    /// РЎРѕРґРµСЂР¶РёРјРѕРµ
    pub content: MessageContent,
    /// РЎС‚Р°С‚СѓСЃ РґРѕСЃС‚Р°РІРєРё
    pub status: MessageStatus,
    /// Р’СЂРµРјСЏ СЃРѕР·РґР°РЅРёСЏ (ms UTC)
    pub created_at: i64,
    /// Р’РµРєС‚РѕСЂРЅС‹Рµ С‡Р°СЃС‹ (JSON)
    pub vector_clock: String,
    /// ID СЃРѕРѕР±С‰РµРЅРёСЏ РЅР° РєРѕС‚РѕСЂРѕРµ РѕС‚РІРµС‡Р°РµРј
    pub reply_to: Option<String>,
    /// РЎРѕРѕР±С‰РµРЅРёРµ СѓРґР°Р»РµРЅРѕ?
    pub is_deleted: bool,
    /// Р’СЂРµРјСЏ СѓРґР°Р»РµРЅРёСЏ (ms UTC)
    pub deleted_at: Option<i64>,
}

impl Message {
    pub fn new(id: String, chat_id: String, sender_id: String, content: MessageContent) -> Self {
        Self {
            id,
            chat_id,
            sender_id,
            content,
            status: MessageStatus::Pending,
            created_at: now_ms(),
            vector_clock: "{}".to_string(),
            reply_to: None,
            is_deleted: false,
            deleted_at: None,
        }
    }

    pub fn with_reply(mut self, reply_to: String) -> Self {
        self.reply_to = Some(reply_to);
        self
    }

    pub fn mark_deleted(&mut self) {
        self.is_deleted = true;
        self.deleted_at = Some(now_ms());
    }

    pub fn is_text(&self) -> bool {
        matches!(self.content, MessageContent::Text(_))
    }
}

// ============================================================
// ChatMember
// ============================================================

/// Р РѕР»СЊ СѓС‡Р°СЃС‚РЅРёРєР° РІ С‡Р°С‚Рµ
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MemberRole {
    Owner,
    Admin,
    Member,
}

impl MemberRole {
    pub fn as_str(&self) -> &'static str {
        match self {
            MemberRole::Owner => "owner",
            MemberRole::Admin => "admin",
            MemberRole::Member => "member",
        }
    }

    pub fn from_db_str(s: &str) -> Self {
        match s {
            "owner" => MemberRole::Owner,
            "admin" => MemberRole::Admin,
            _ => MemberRole::Member,
        }
    }
}

/// РЈС‡Р°СЃС‚РЅРёРє С‡Р°С‚Р°
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMember {
    pub chat_id: String,
    pub user_id: String,
    pub role: MemberRole,
    pub joined_at: i64,
}

impl ChatMember {
    pub fn new(chat_id: String, user_id: String, role: MemberRole) -> Self {
        Self {
            chat_id,
            user_id,
            role,
            joined_at: now_ms(),
        }
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    // --- User ---

    #[test]
    fn test_user_new() {
        let u = User::new("id1".into(), "Alice".into());
        assert_eq!(u.id, "id1");
        assert_eq!(u.display_name, "Alice");
        assert!(!u.is_self);
        assert!(u.avatar.is_none());
        assert!(u.created_at > 0);
    }

    #[test]
    fn test_user_new_self() {
        let u = User::new_self("id2".into(), "Me".into());
        assert!(u.is_self);
    }

    // --- ChatKind ---

    #[test]
    fn test_chat_kind_roundtrip() {
        assert_eq!(
            ChatKind::from_db_str(ChatKind::Direct.as_str()),
            ChatKind::Direct
        );
        assert_eq!(
            ChatKind::from_db_str(ChatKind::Group.as_str()),
            ChatKind::Group
        );
    }

    #[test]
    fn test_chat_kind_unknown_defaults_to_direct() {
        assert_eq!(ChatKind::from_db_str("unknown"), ChatKind::Direct);
    }

    // --- Chat ---

    #[test]
    fn test_chat_new_direct() {
        let c = Chat::new_direct("chat1".into());
        assert_eq!(c.kind, ChatKind::Direct);
        assert!(c.title.is_none());
        assert_eq!(c.unread_count, 0);
    }

    #[test]
    fn test_chat_new_group() {
        let c = Chat::new_group("chat2".into(), "My Group".into());
        assert_eq!(c.kind, ChatKind::Group);
        assert_eq!(c.title.unwrap(), "My Group");
    }

    // --- MessageStatus ---

    #[test]
    fn test_message_status_roundtrip() {
        let statuses = [
            MessageStatus::Pending,
            MessageStatus::Sent,
            MessageStatus::Delivered,
            MessageStatus::Read,
            MessageStatus::Failed,
        ];
        for s in &statuses {
            assert_eq!(MessageStatus::from_db_str(s.as_str()), *s);
        }
    }

    #[test]
    fn test_message_status_unknown_defaults_pending() {
        assert_eq!(
            MessageStatus::from_db_str("unknown"),
            MessageStatus::Pending
        );
    }

    // --- MessageContent ---

    #[test]
    fn test_message_content_text_kind() {
        let c = MessageContent::Text("hello".into());
        assert_eq!(c.kind(), "text");
        assert_eq!(c.as_text(), Some("hello"));
    }

    #[test]
    fn test_message_content_system_kind() {
        let c = MessageContent::System("user joined".into());
        assert_eq!(c.kind(), "system");
        assert!(c.as_text().is_none());
    }

    #[test]
    fn test_message_content_file_kind() {
        let c = MessageContent::File {
            name: "photo.jpg".into(),
            size: 1024,
            hash: "abc123".into(),
        };
        assert_eq!(c.kind(), "file");
    }

    // --- Message ---

    #[test]
    fn test_message_new() {
        let m = Message::new(
            "msg1".into(),
            "chat1".into(),
            "user1".into(),
            MessageContent::Text("Hi!".into()),
        );
        assert_eq!(m.status, MessageStatus::Pending);
        assert!(!m.is_deleted);
        assert!(m.reply_to.is_none());
        assert!(m.is_text());
    }

    #[test]
    fn test_message_with_reply() {
        let m = Message::new(
            "msg2".into(),
            "chat1".into(),
            "user1".into(),
            MessageContent::Text("Reply".into()),
        )
        .with_reply("msg1".into());
        assert_eq!(m.reply_to, Some("msg1".into()));
    }

    #[test]
    fn test_message_mark_deleted() {
        let mut m = Message::new(
            "msg3".into(),
            "chat1".into(),
            "user1".into(),
            MessageContent::Text("delete me".into()),
        );
        m.mark_deleted();
        assert!(m.is_deleted);
        assert!(m.deleted_at.is_some());
    }

    #[test]
    fn test_message_is_text_false_for_system() {
        let m = Message::new(
            "msg4".into(),
            "chat1".into(),
            "sys".into(),
            MessageContent::System("joined".into()),
        );
        assert!(!m.is_text());
    }

    // --- MemberRole ---

    #[test]
    fn test_member_role_roundtrip() {
        let roles = [MemberRole::Owner, MemberRole::Admin, MemberRole::Member];
        for r in &roles {
            assert_eq!(MemberRole::from_db_str(r.as_str()), *r);
        }
    }

    #[test]
    fn test_member_role_unknown_defaults_member() {
        assert_eq!(MemberRole::from_db_str("unknown"), MemberRole::Member);
    }

    // --- ChatMember ---

    #[test]
    fn test_chat_member_new() {
        let cm = ChatMember::new("chat1".into(), "user1".into(), MemberRole::Admin);
        assert_eq!(cm.role, MemberRole::Admin);
        assert!(cm.joined_at > 0);
    }

    // --- now_ms ---

    #[test]
    fn test_now_ms_positive() {
        assert!(now_ms() > 0);
    }

    #[test]
    fn test_now_ms_increases() {
        let t1 = now_ms();
        std::thread::sleep(std::time::Duration::from_millis(1));
        let t2 = now_ms();
        assert!(t2 >= t1);
    }
}
