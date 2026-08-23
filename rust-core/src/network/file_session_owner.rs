//! Bounded owner for persistent authenticated F4 sender sessions.
//!
//! This module deliberately does not extend the legacy unauthenticated `ConnectionPool`. One owner
//! holds one reusable QUIC endpoint and keys sessions by an externally pinned Ed25519 public key plus
//! network path. Per-peer state is serialized across connect and stream operations, so concurrent
//! callers cannot create duplicate authenticated sessions.
//!
//! Failed sessions are removed before a later retry. A retry creates a new QUIC connection and runs
//! the complete TLS-exporter-bound C1 handshake again. Idle entries are bounded and explicitly
//! evictable. Shutdown prevents new work, closes the endpoint, and drains all owned sessions.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

use crate::crypto::keys::{NodeId, ED25519_PUBLIC_KEY_SIZE};
use crate::crypto::signing_identity::InstalledSigningIdentity;
use crate::network::file_control::{SignedFileControlV1, FILE_CONTROL_ID_BYTES};
use crate::network::file_session::{
    FileParallelWindowReport, FileSendSession, FileSessionError, FileSessionLimits,
    FileSessionPeer, FileSessionWindowLimits, FileSessionWindowReport,
};
use crate::network::file_wire::{
    FileCapabilitiesV1, FileFrameV1, FileWireError, FEATURE_CHUNK_RANGE_FRAMES,
    MAX_FILE_FRAME_PAYLOAD_BYTES, REQUIRED_FILE_FEATURES_V1,
};
use crate::network::quic_client::{QuicClient, QuicClientError};

pub const MAX_OWNED_FILE_SESSION_PEERS: usize = 256;
const FILE_SESSION_SERVER_NAME: &str = "p2p-messenger";
const MODERN_NODE_ID_BYTES: usize = 67;
const LEGACY_NODE_ID_BYTES: usize = 35;

#[derive(Debug, Clone)]
pub struct FileSessionOwnerConfig {
    pub max_peers: usize,
    pub idle_timeout: Duration,
    pub connect_timeout: Duration,
    pub capabilities: FileCapabilitiesV1,
    pub session_limits: FileSessionLimits,
}

impl Default for FileSessionOwnerConfig {
    fn default() -> Self {
        Self {
            max_peers: 32,
            idle_timeout: Duration::from_secs(300),
            connect_timeout: Duration::from_secs(10),
            capabilities: FileCapabilitiesV1 {
                min_protocol_version: 1,
                max_protocol_version: 1,
                max_parallel_streams: 4,
                mandatory_features: REQUIRED_FILE_FEATURES_V1,
                optional_features: FEATURE_CHUNK_RANGE_FRAMES,
                max_frame_payload_bytes: MAX_FILE_FRAME_PAYLOAD_BYTES as u32,
            },
            session_limits: FileSessionLimits::default(),
        }
    }
}

/// One externally authenticated peer identity at one current network path.
///
/// The constructor validates canonical identity shape and modern key binding. A legacy routing ID is
/// accepted only together with the exact Ed25519 key already pinned by contact/identity state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileSessionTarget {
    peer: FileSessionPeer,
    remote_address: SocketAddr,
}

impl FileSessionTarget {
    pub fn new(
        peer: FileSessionPeer,
        remote_address: SocketAddr,
    ) -> Result<Self, FileSessionOwnerError> {
        validate_peer(&peer)?;
        if remote_address.port() == 0 || remote_address.ip().is_unspecified() {
            return Err(FileSessionOwnerError::InvalidTarget(
                "remote address must be concrete and have a non-zero port",
            ));
        }
        Ok(Self {
            peer,
            remote_address,
        })
    }

    pub fn peer(&self) -> &FileSessionPeer {
        &self.peer
    }

    pub fn remote_address(&self) -> SocketAddr {
        self.remote_address
    }
}

#[derive(Debug, thiserror::Error)]
pub enum FileSessionOwnerError {
    #[error("invalid file session owner configuration: {0}")]
    InvalidConfig(&'static str),
    #[error("invalid pinned file session target: {0}")]
    InvalidTarget(&'static str),
    #[error("same Ed25519 peer/path was requested with a conflicting node identity")]
    PeerIdentityConflict,
    #[error("file session owner reached its bounded peer limit {max}")]
    Capacity { max: usize },
    #[error("file session owner has been shut down")]
    Shutdown,
    #[error("the shared authenticated session attempt failed")]
    SessionUnavailable,
    #[error("file session owner connect timed out")]
    ConnectTimeout,
    #[error("QUIC file session owner error: {0}")]
    Quic(#[from] QuicClientError),
    #[error("authenticated file session error: {0}")]
    Session(#[from] FileSessionError),
    #[error("file session capability error: {0}")]
    Wire(#[from] FileWireError),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct PeerPathKey {
    ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    remote_address: SocketAddr,
}

impl PeerPathKey {
    fn from_target(target: &FileSessionTarget) -> Self {
        Self {
            ed25519_public_key: target.peer.ed25519_public_key,
            remote_address: target.remote_address,
        }
    }
}

enum OwnedSessionState {
    Vacant,
    Ready(FileSendSession),
    Failed,
}

struct OwnedSessionSlot {
    peer: FileSessionPeer,
    remote_address: SocketAddr,
    session: Mutex<OwnedSessionState>,
    last_used: StdMutex<Instant>,
}

impl OwnedSessionSlot {
    fn new(target: &FileSessionTarget) -> Self {
        Self {
            peer: target.peer.clone(),
            remote_address: target.remote_address,
            session: Mutex::new(OwnedSessionState::Vacant),
            last_used: StdMutex::new(Instant::now()),
        }
    }

    fn touch(&self) {
        let mut last_used = self
            .last_used
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *last_used = Instant::now();
    }

    fn idle_for(&self, now: Instant) -> Duration {
        let last_used = self
            .last_used
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        now.saturating_duration_since(*last_used)
    }
}

struct FileSessionOwnerState {
    entries: HashMap<PeerPathKey, Arc<OwnedSessionSlot>>,
}

/// Owns one reusable endpoint and at most one C1 sender session per pinned peer/path.
pub struct FileSessionOwner {
    endpoint: Arc<QuicClient>,
    local_identity: Arc<InstalledSigningIdentity>,
    config: FileSessionOwnerConfig,
    state: Mutex<FileSessionOwnerState>,
    shutdown: AtomicBool,
}

impl FileSessionOwner {
    pub fn new(
        bind_address: SocketAddr,
        local_identity: Arc<InstalledSigningIdentity>,
        config: FileSessionOwnerConfig,
    ) -> Result<Self, FileSessionOwnerError> {
        validate_config(&config)?;
        let endpoint = Arc::new(QuicClient::new(bind_address)?);
        Ok(Self {
            endpoint,
            local_identity,
            config,
            state: Mutex::new(FileSessionOwnerState {
                entries: HashMap::new(),
            }),
            shutdown: AtomicBool::new(false),
        })
    }

    pub fn local_address(&self) -> SocketAddr {
        self.endpoint.local_address()
    }

    /// Return the authenticated C1 scope, connecting only when no reusable session exists.
    /// This is also the smallest host-facing ownership seam used by loopback tests.
    pub async fn session_scope(
        &self,
        target: &FileSessionTarget,
        now_ms: i64,
    ) -> Result<[u8; FILE_CONTROL_ID_BYTES], FileSessionOwnerError> {
        let slot = self.ensure_session(target, now_ms).await?;
        let state = slot.session.lock().await;
        match &*state {
            OwnedSessionState::Ready(session) => {
                slot.touch();
                Ok(session.scope_id())
            }
            OwnedSessionState::Vacant | OwnedSessionState::Failed => {
                Err(FileSessionOwnerError::SessionUnavailable)
            }
        }
    }

    /// Send one bounded B1 frame on the persistent ordered session.
    pub async fn send_chunk(
        &self,
        target: &FileSessionTarget,
        frame: &FileFrameV1,
        now_ms: i64,
    ) -> Result<(), FileSessionOwnerError> {
        let slot = self.ensure_session(target, now_ms).await?;
        let mut state = slot.session.lock().await;
        let result = match &mut *state {
            OwnedSessionState::Ready(session) => session.send_chunk(frame).await,
            OwnedSessionState::Vacant | OwnedSessionState::Failed => {
                return Err(FileSessionOwnerError::SessionUnavailable);
            }
        };
        match result {
            Ok(()) => {
                slot.touch();
                Ok(())
            }
            Err(error) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                Err(FileSessionOwnerError::Session(error))
            }
        }
    }

    /// Send one bounded ACK window without releasing per-peer session ownership between frames.
    pub async fn send_chunk_window(
        &self,
        target: &FileSessionTarget,
        frames: &[FileFrameV1],
        window_limits: FileSessionWindowLimits,
        now_ms: i64,
    ) -> Result<FileSessionWindowReport, FileSessionOwnerError> {
        let slot = self.ensure_session(target, now_ms).await?;
        let mut state = slot.session.lock().await;
        let result = match &mut *state {
            OwnedSessionState::Ready(session) => {
                session.send_chunk_window(frames, window_limits).await
            }
            OwnedSessionState::Vacant | OwnedSessionState::Failed => {
                return Err(FileSessionOwnerError::SessionUnavailable);
            }
        };
        match result {
            Ok(report) => {
                slot.touch();
                Ok(report)
            }
            Err(error) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                Err(FileSessionOwnerError::Session(error))
            }
        }
    }

    /// Run bounded windows concurrently on independent data streams of the one authenticated
    /// connection. Any stream failure evicts the session so MissingRanges can resume on fresh auth.
    pub async fn send_parallel_windows(
        &self,
        target: &FileSessionTarget,
        windows: Vec<Vec<FileFrameV1>>,
        window_limits: FileSessionWindowLimits,
        now_ms: i64,
    ) -> Result<Vec<FileParallelWindowReport>, FileSessionOwnerError> {
        let slot = self.ensure_session(target, now_ms).await?;
        let mut state = slot.session.lock().await;
        let result = match &mut *state {
            OwnedSessionState::Ready(session) => {
                session.send_parallel_windows(windows, window_limits).await
            }
            OwnedSessionState::Vacant | OwnedSessionState::Failed => {
                return Err(FileSessionOwnerError::SessionUnavailable);
            }
        };
        match result {
            Ok(reports) => {
                slot.touch();
                Ok(reports)
            }
            Err(error) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                Err(FileSessionOwnerError::Session(error))
            }
        }
    }

    /// Receive one independently signed MissingRanges/control record on the persistent session.
    pub async fn receive_control(
        &self,
        target: &FileSessionTarget,
        expected_scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        now_ms: i64,
        last_seen_sequence: Option<u64>,
    ) -> Result<SignedFileControlV1, FileSessionOwnerError> {
        let slot = self.ensure_session(target, now_ms).await?;
        let mut state = slot.session.lock().await;
        let result = match &mut *state {
            OwnedSessionState::Ready(session) => {
                session
                    .receive_control(expected_scope_id, now_ms, last_seen_sequence)
                    .await
            }
            OwnedSessionState::Vacant | OwnedSessionState::Failed => {
                return Err(FileSessionOwnerError::SessionUnavailable);
            }
        };
        match result {
            Ok(control) => {
                slot.touch();
                Ok(control)
            }
            Err(error) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                Err(FileSessionOwnerError::Session(error))
            }
        }
    }

    pub async fn owned_peer_count(&self) -> usize {
        self.state.lock().await.entries.len()
    }

    /// Evict entries idle for at least the configured timeout. Active/connect-in-progress entries
    /// hold an extra `Arc` and are never selected by this pass.
    pub async fn evict_idle(&self) -> usize {
        let evicted = {
            let mut state = self.state.lock().await;
            collect_idle_slots(&mut state.entries, self.config.idle_timeout)
        };
        let count = evicted.len();
        for slot in evicted {
            close_slot(slot).await;
        }
        count
    }

    /// Idempotent explicit shutdown. It rejects all future work and closes the one owned endpoint.
    pub async fn shutdown(&self) {
        if self.shutdown.swap(true, Ordering::AcqRel) {
            return;
        }
        let slots = {
            let mut state = self.state.lock().await;
            state.entries.drain().map(|(_, slot)| slot).collect::<Vec<_>>()
        };
        // Closing the endpoint first also cancels a connect/handshake currently holding a slot lock.
        self.endpoint.close().await;
        for slot in slots {
            close_slot(slot).await;
        }
    }

    async fn ensure_session(
        &self,
        target: &FileSessionTarget,
        now_ms: i64,
    ) -> Result<Arc<OwnedSessionSlot>, FileSessionOwnerError> {
        let slot = self.slot_for(target).await?;
        let mut state = slot.session.lock().await;
        match &*state {
            OwnedSessionState::Ready(_) => {
                slot.touch();
                return Ok(slot.clone());
            }
            OwnedSessionState::Failed => {
                return Err(FileSessionOwnerError::SessionUnavailable);
            }
            OwnedSessionState::Vacant => {}
        }

        // This per-slot mutex remains held across connect + C1 auth. Concurrent callers for the same
        // pinned peer/path wait here and observe the one shared outcome instead of dialing twice.
        let connect_result = tokio::time::timeout(self.config.connect_timeout, async {
            let connection = self
                .endpoint
                .connect(slot.remote_address, FILE_SESSION_SERVER_NAME)
                .await?;
            let session = FileSendSession::connect_with_signer(
                &connection,
                self.local_identity.legacy_routing_node_id(),
                &self.local_identity,
                slot.peer.clone(),
                self.config.capabilities.clone(),
                now_ms,
                self.config.session_limits,
            )
            .await?;
            Ok::<FileSendSession, FileSessionOwnerError>(session)
        })
        .await;

        let session = match connect_result {
            Ok(Ok(session)) => session,
            Ok(Err(error)) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                return Err(error);
            }
            Err(_) => {
                *state = OwnedSessionState::Failed;
                drop(state);
                self.remove_slot_if_same(target, &slot).await;
                return Err(FileSessionOwnerError::ConnectTimeout);
            }
        };

        if self.shutdown.load(Ordering::Acquire) {
            *state = OwnedSessionState::Failed;
            drop(state);
            let _ = session.close().await;
            self.remove_slot_if_same(target, &slot).await;
            return Err(FileSessionOwnerError::Shutdown);
        }

        *state = OwnedSessionState::Ready(session);
        slot.touch();
        drop(state);
        Ok(slot)
    }

    async fn slot_for(
        &self,
        target: &FileSessionTarget,
    ) -> Result<Arc<OwnedSessionSlot>, FileSessionOwnerError> {
        if self.shutdown.load(Ordering::Acquire) {
            return Err(FileSessionOwnerError::Shutdown);
        }
        let key = PeerPathKey::from_target(target);
        let (slot, evicted) = {
            let mut state = self.state.lock().await;
            if self.shutdown.load(Ordering::Acquire) {
                return Err(FileSessionOwnerError::Shutdown);
            }
            if let Some(existing) = state.entries.get(&key) {
                if existing.peer.node_id != target.peer.node_id {
                    return Err(FileSessionOwnerError::PeerIdentityConflict);
                }
                return Ok(existing.clone());
            }

            let evicted = collect_idle_slots(&mut state.entries, self.config.idle_timeout);
            if state.entries.len() >= self.config.max_peers {
                drop(state);
                for slot in evicted {
                    close_slot(slot).await;
                }
                return Err(FileSessionOwnerError::Capacity {
                    max: self.config.max_peers,
                });
            }
            let slot = Arc::new(OwnedSessionSlot::new(target));
            state.entries.insert(key, slot.clone());
            (slot, evicted)
        };
        for idle in evicted {
            close_slot(idle).await;
        }
        Ok(slot)
    }

    async fn remove_slot_if_same(
        &self,
        target: &FileSessionTarget,
        slot: &Arc<OwnedSessionSlot>,
    ) {
        let key = PeerPathKey::from_target(target);
        let mut state = self.state.lock().await;
        let remove = state
            .entries
            .get(&key)
            .map(|current| Arc::ptr_eq(current, slot))
            .unwrap_or(false);
        if remove {
            state.entries.remove(&key);
        }
    }
}

fn validate_config(config: &FileSessionOwnerConfig) -> Result<(), FileSessionOwnerError> {
    if config.max_peers == 0 || config.max_peers > MAX_OWNED_FILE_SESSION_PEERS {
        return Err(FileSessionOwnerError::InvalidConfig(
            "max peers must be within the hard owner bound",
        ));
    }
    if config.idle_timeout.is_zero()
        || config.connect_timeout.is_zero()
        || config.session_limits.operation_timeout.is_zero()
        || config.session_limits.durable_write_timeout.is_zero()
    {
        return Err(FileSessionOwnerError::InvalidConfig(
            "owner and session timeouts must be non-zero",
        ));
    }
    config.capabilities.validate()?;
    Ok(())
}

fn validate_peer(peer: &FileSessionPeer) -> Result<(), FileSessionOwnerError> {
    if peer.ed25519_public_key.iter().all(|byte| *byte == 0)
        || !matches!(peer.node_id.len(), LEGACY_NODE_ID_BYTES | MODERN_NODE_ID_BYTES)
        || !peer.node_id.starts_with("pk_")
        || !peer.node_id[3..]
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(FileSessionOwnerError::InvalidTarget(
            "peer identity is not canonical",
        ));
    }
    if peer.node_id.len() == MODERN_NODE_ID_BYTES {
        let expected = format!(
            "pk_{}",
            NodeId::from_ed25519_pubkey(&peer.ed25519_public_key).to_hex()
        );
        if peer.node_id != expected {
            return Err(FileSessionOwnerError::InvalidTarget(
                "modern node ID does not match the pinned Ed25519 key",
            ));
        }
    }
    Ok(())
}

fn collect_idle_slots(
    entries: &mut HashMap<PeerPathKey, Arc<OwnedSessionSlot>>,
    idle_timeout: Duration,
) -> Vec<Arc<OwnedSessionSlot>> {
    let now = Instant::now();
    let keys = entries
        .iter()
        .filter_map(|(key, slot)| {
            (Arc::strong_count(slot) == 1 && slot.idle_for(now) >= idle_timeout).then_some(*key)
        })
        .collect::<Vec<_>>();
    keys.into_iter()
        .filter_map(|key| entries.remove(&key))
        .collect()
}

async fn close_slot(slot: Arc<OwnedSessionSlot>) {
    let previous = {
        let mut state = slot.session.lock().await;
        std::mem::replace(&mut *state, OwnedSessionState::Failed)
    };
    if let OwnedSessionState::Ready(session) = previous {
        let _ = session.close().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::future::Future;
    use std::net::{IpAddr, Ipv4Addr};
    use std::pin::Pin;
    use std::sync::atomic::AtomicUsize;

    use tokio::sync::Barrier;

    use crate::network::file_control::{
        sign_file_control_with_signer_v1, FileChunkRangePageV1, FileChunkRangeV1,
        FileControlBodyV1, FileControlClaimsV1, FileControlError,
    };
    use crate::network::file_session::{DurableFileRangeSink, FileReceiveSession, FileSessionAdmission};
    use crate::network::file_wire::FileChunkDataV1;

    const NOW: i64 = 1_900_000_000_000;

    struct MemoryAdmission;

    impl FileSessionAdmission for MemoryAdmission {
        fn admit_session(
            &mut self,
            _peer_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
            _scope_id: &[u8; FILE_CONTROL_ID_BYTES],
            _expires_at_ms: i64,
        ) -> Result<bool, String> {
            Ok(true)
        }
    }

    struct DiscardSink;

    impl DurableFileRangeSink for DiscardSink {
        fn persist_range<'a>(
            &'a mut self,
            _range: &'a FileChunkDataV1,
        ) -> Pin<Box<dyn Future<Output = Result<(), String>> + Send + 'a>> {
            Box::pin(async { Ok(()) })
        }
    }

    fn any_port() -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)
    }

    fn identity(secret: u8) -> Arc<InstalledSigningIdentity> {
        Arc::new(
            InstalledSigningIdentity::from_seed(
                1,
                format!("pk_{}", format!("{secret:02x}").repeat(16)),
                &[secret; 32],
            )
            .unwrap(),
        )
    }

    fn peer(identity: &InstalledSigningIdentity) -> FileSessionPeer {
        FileSessionPeer {
            node_id: identity.legacy_routing_node_id().to_owned(),
            ed25519_public_key: identity.public_key().try_into().unwrap(),
        }
    }

    fn config(max_peers: usize, idle_timeout: Duration) -> FileSessionOwnerConfig {
        FileSessionOwnerConfig {
            max_peers,
            idle_timeout,
            connect_timeout: Duration::from_secs(2),
            capabilities: FileSessionOwnerConfig::default().capabilities,
            session_limits: FileSessionLimits {
                operation_timeout: Duration::from_secs(2),
                durable_write_timeout: Duration::from_secs(2),
            },
        }
    }

    fn spawn_receivers(
        endpoint: Arc<QuicClient>,
        server_identity: Arc<InstalledSigningIdentity>,
        client_identity: Arc<InstalledSigningIdentity>,
        expected_connections: usize,
        accepted: Arc<AtomicUsize>,
    ) -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            let mut handlers = Vec::new();
            for _ in 0..expected_connections {
                let connection = endpoint.accept().await.unwrap();
                accepted.fetch_add(1, Ordering::AcqRel);
                let server_identity = server_identity.clone();
                let client_identity = client_identity.clone();
                handlers.push(tokio::spawn(async move {
                    let mut admission = MemoryAdmission;
                    let accepted = FileReceiveSession::accept_with_signer(
                        &connection,
                        server_identity.legacy_routing_node_id(),
                        &server_identity,
                        peer(&client_identity),
                        FileSessionOwnerConfig::default().capabilities,
                        NOW,
                        FileSessionLimits {
                            operation_timeout: Duration::from_secs(2),
                            durable_write_timeout: Duration::from_secs(2),
                        },
                        &mut admission,
                    )
                    .await;
                    if let Ok(mut session) = accepted {
                        let mut sink = DiscardSink;
                        let _ = session.receive_chunk(&mut sink).await;
                    }
                }));
            }
            for handler in handlers {
                handler.await.unwrap();
            }
        })
    }

    fn setup(
        max_peers: usize,
        idle_timeout: Duration,
        expected_connections: usize,
    ) -> (
        Arc<FileSessionOwner>,
        FileSessionTarget,
        Arc<AtomicUsize>,
        tokio::task::JoinHandle<()>,
    ) {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_address = server_endpoint.local_address();
        let client_identity = identity(51);
        let server_identity = identity(52);
        let accepted = Arc::new(AtomicUsize::new(0));
        let server_task = spawn_receivers(
            server_endpoint,
            server_identity.clone(),
            client_identity.clone(),
            expected_connections,
            accepted.clone(),
        );
        let owner = Arc::new(
            FileSessionOwner::new(
                any_port(),
                client_identity,
                config(max_peers, idle_timeout),
            )
            .unwrap(),
        );
        let target = FileSessionTarget::new(peer(&server_identity), server_address).unwrap();
        (owner, target, accepted, server_task)
    }

    #[tokio::test]
    async fn reusable_endpoint_and_authenticated_session_keep_one_scope() {
        let (owner, target, accepted, server_task) =
            setup(4, Duration::from_secs(60), 1);
        let local_address = owner.local_address();
        let first = owner.session_scope(&target, NOW).await.unwrap();
        let second = owner.session_scope(&target, NOW + 1).await.unwrap();

        assert_eq!(first, second);
        assert_eq!(owner.local_address(), local_address);
        assert_eq!(owner.owned_peer_count().await, 1);
        assert_eq!(accepted.load(Ordering::Acquire), 1);

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn concurrent_connect_is_single_flight_without_duplicate_connections() {
        const CALLERS: usize = 16;
        let (owner, target, accepted, server_task) =
            setup(4, Duration::from_secs(60), 1);
        let barrier = Arc::new(Barrier::new(CALLERS));
        let mut tasks = Vec::new();
        for _ in 0..CALLERS {
            let owner = owner.clone();
            let target = target.clone();
            let barrier = barrier.clone();
            tasks.push(tokio::spawn(async move {
                barrier.wait().await;
                owner.session_scope(&target, NOW).await.unwrap()
            }));
        }
        let mut scopes = Vec::new();
        for task in tasks {
            scopes.push(task.await.unwrap());
        }

        assert!(scopes.iter().all(|scope| *scope == scopes[0]));
        assert_eq!(accepted.load(Ordering::Acquire), 1);
        assert_eq!(owner.owned_peer_count().await, 1);

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn idle_eviction_reconnects_with_fresh_exporter_bound_scope() {
        let (owner, target, accepted, server_task) =
            setup(4, Duration::from_millis(20), 2);
        let first = owner.session_scope(&target, NOW).await.unwrap();
        tokio::time::sleep(Duration::from_millis(40)).await;
        assert_eq!(owner.evict_idle().await, 1);
        assert_eq!(owner.owned_peer_count().await, 0);

        let second = owner.session_scope(&target, NOW + 1).await.unwrap();
        assert_ne!(first, second);
        assert_eq!(accepted.load(Ordering::Acquire), 2);

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn reconnect_preserves_signed_missing_ranges_control_seam() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_address = server_endpoint.local_address();
        let client_identity = identity(56);
        let server_identity = identity(57);
        let transfer_id = [0x91; FILE_CONTROL_ID_BYTES];
        let server_task = {
            let server_endpoint = server_endpoint.clone();
            let client_identity = client_identity.clone();
            let server_identity = server_identity.clone();
            tokio::spawn(async move {
                let first_connection = server_endpoint.accept().await.unwrap();
                let mut first_admission = MemoryAdmission;
                let mut first = FileReceiveSession::accept_with_signer(
                    &first_connection,
                    server_identity.legacy_routing_node_id(),
                    &server_identity,
                    peer(&client_identity),
                    FileSessionOwnerConfig::default().capabilities,
                    NOW,
                    FileSessionLimits {
                        operation_timeout: Duration::from_secs(2),
                        durable_write_timeout: Duration::from_secs(2),
                    },
                    &mut first_admission,
                )
                .await
                .unwrap();
                let mut sink = DiscardSink;
                // Idle eviction closes the whole owned stream/connection. Depending on QUIC task
                // scheduling the peer may observe the logical CLOSE record or transport closure.
                assert!(first.receive_chunk(&mut sink).await.is_err());

                let second_connection = server_endpoint.accept().await.unwrap();
                let mut second_admission = MemoryAdmission;
                let mut second = FileReceiveSession::accept_with_signer(
                    &second_connection,
                    server_identity.legacy_routing_node_id(),
                    &server_identity,
                    peer(&client_identity),
                    FileSessionOwnerConfig::default().capabilities,
                    NOW + 1,
                    FileSessionLimits {
                        operation_timeout: Duration::from_secs(2),
                        durable_write_timeout: Duration::from_secs(2),
                    },
                    &mut second_admission,
                )
                .await
                .unwrap();
                let missing = sign_file_control_with_signer_v1(
                    FileControlClaimsV1 {
                        record_id: [0x92; FILE_CONTROL_ID_BYTES],
                        scope_id: transfer_id,
                        sequence: 1,
                        created_at_ms: NOW,
                        expires_at_ms: NOW + 60_000,
                        signer_node_id: server_identity.legacy_routing_node_id().to_owned(),
                        recipient_node_id: client_identity.legacy_routing_node_id().to_owned(),
                        body: FileControlBodyV1::MissingRanges(FileChunkRangePageV1 {
                            batch_id: [0x93; FILE_CONTROL_ID_BYTES],
                            page_index: 0,
                            is_last_page: true,
                            total_chunk_count: 2,
                            ranges: vec![FileChunkRangeV1 {
                                start_chunk: 1,
                                end_chunk_exclusive: 2,
                            }],
                        }),
                    },
                    &server_identity,
                )
                .unwrap();
                second.send_control(&missing).await.unwrap();
                let _ = second.receive_chunk(&mut sink).await;
            })
        };
        let owner = FileSessionOwner::new(
            any_port(),
            client_identity,
            config(4, Duration::from_millis(20)),
        )
        .unwrap();
        let target = FileSessionTarget::new(peer(&server_identity), server_address).unwrap();

        let first_scope = owner.session_scope(&target, NOW).await.unwrap();
        tokio::time::sleep(Duration::from_millis(40)).await;
        assert_eq!(owner.evict_idle().await, 1);
        let second_scope = owner.session_scope(&target, NOW + 1).await.unwrap();
        assert_ne!(first_scope, second_scope);
        let control = owner
            .receive_control(&target, &transfer_id, NOW + 1, None)
            .await
            .unwrap();
        assert!(matches!(
            control.claims.body,
            FileControlBodyV1::MissingRanges(_)
        ));

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn wrong_pinned_peer_fails_closed_is_evicted_and_can_reconnect_correctly() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_address = server_endpoint.local_address();
        let client_identity = identity(61);
        let server_identity = identity(62);
        let wrong_identity = identity(63);
        let accepted = Arc::new(AtomicUsize::new(0));
        let server_task = spawn_receivers(
            server_endpoint,
            server_identity.clone(),
            client_identity.clone(),
            2,
            accepted.clone(),
        );
        let owner = FileSessionOwner::new(
            any_port(),
            client_identity,
            config(4, Duration::from_secs(60)),
        )
        .unwrap();
        // Keep the actual recipient routing ID so the server can answer, but pin the wrong key on
        // the client. This isolates the C1 authenticated-key rejection instead of failing earlier on
        // a recipient-ID mismatch.
        let mut wrong_peer = peer(&server_identity);
        wrong_peer.ed25519_public_key = wrong_identity.public_key().try_into().unwrap();
        let wrong_target = FileSessionTarget::new(wrong_peer, server_address).unwrap();
        let error = owner.session_scope(&wrong_target, NOW).await.unwrap_err();
        assert!(matches!(
            error,
            FileSessionOwnerError::Session(FileSessionError::Control(
                FileControlError::UnexpectedSigner
            ))
        ));
        assert_eq!(owner.owned_peer_count().await, 0);

        let correct_target =
            FileSessionTarget::new(peer(&server_identity), server_address).unwrap();
        let scope = owner
            .session_scope(&correct_target, NOW + 1)
            .await
            .unwrap();
        assert!(scope.iter().any(|byte| *byte != 0));
        assert_eq!(accepted.load(Ordering::Acquire), 2);

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn parallel_operation_failure_evicts_session_before_reconnect() {
        let (owner, target, accepted, server_task) =
            setup(4, Duration::from_secs(60), 2);
        assert!(matches!(
            owner
                .send_parallel_windows(
                    &target,
                    Vec::new(),
                    FileSessionWindowLimits::default(),
                    NOW,
                )
                .await,
            Err(FileSessionOwnerError::Session(
                FileSessionError::ParallelStreamLimit { count: 0, .. }
            ))
        ));
        assert_eq!(owner.owned_peer_count().await, 0);

        let scope = owner.session_scope(&target, NOW + 1).await.unwrap();
        assert!(scope.iter().any(|byte| *byte != 0));
        assert_eq!(accepted.load(Ordering::Acquire), 2);

        owner.shutdown().await;
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn bounded_peer_count_rejects_a_second_non_idle_target() {
        let (owner, first_target, _accepted, first_server_task) =
            setup(1, Duration::from_secs(60), 1);
        owner.session_scope(&first_target, NOW).await.unwrap();

        let second_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let second_identity = identity(72);
        let second_target = FileSessionTarget::new(
            peer(&second_identity),
            second_endpoint.local_address(),
        )
        .unwrap();
        assert!(matches!(
            owner.session_scope(&second_target, NOW).await,
            Err(FileSessionOwnerError::Capacity { max: 1 })
        ));
        assert_eq!(owner.owned_peer_count().await, 1);

        owner.shutdown().await;
        second_endpoint.close().await;
        first_server_task.await.unwrap();
    }

    #[tokio::test]
    async fn explicit_shutdown_drains_sessions_and_rejects_new_work() {
        let (owner, target, accepted, server_task) =
            setup(4, Duration::from_secs(60), 1);
        owner.session_scope(&target, NOW).await.unwrap();
        assert_eq!(accepted.load(Ordering::Acquire), 1);

        owner.shutdown().await;
        assert_eq!(owner.owned_peer_count().await, 0);
        assert!(matches!(
            owner.session_scope(&target, NOW + 1).await,
            Err(FileSessionOwnerError::Shutdown)
        ));
        owner.shutdown().await;
        server_task.await.unwrap();
    }
}
