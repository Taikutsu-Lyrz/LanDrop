use std::collections::HashMap;
use std::env;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use tokio::sync::{mpsc, oneshot, Mutex as AsyncMutex, Notify, RwLock};
use tokio::task;
use tracing::warn;
use uuid::Uuid;

use crate::config::persistence;
use crate::config::settings::AppSettings;
use crate::config::settings::{
    clamp_concurrent_file_streams, QuickSaveMode, ThemePreference, TrustedDevice,
};
use crate::discovery;
use crate::transfer;
use crate::types::{
    now_ms, AppBootstrap, IncomingTransferDecision, IncomingTransferOffer, LocalAdapter,
    PeerDevice, RemoteAccessStatus, SendDraft, TransferStatus, TransferSummary,
};

#[derive(Clone)]
pub struct AppState {
    inner: Arc<AppStateInner>,
}

struct AppStateInner {
    settings: RwLock<AppSettings>,
    local_adapters: RwLock<Vec<LocalAdapter>>,
    peers: RwLock<Vec<PeerDevice>>,
    incoming_offer: RwLock<Option<IncomingTransferOffer>>,
    send_draft: RwLock<Option<SendDraft>>,
    transfers: RwLock<Vec<TransferSummary>>,
    launched_at_ms: u64,
    device_id: String,
    instance_id: String,
    persistence_path: RwLock<Option<PathBuf>>,
    persistence_write_lock: AsyncMutex<()>,
    discovery_tx: Mutex<Option<mpsc::UnboundedSender<discovery::service::DiscoveryCommand>>>,
    pending_incoming: AsyncMutex<HashMap<String, oneshot::Sender<IncomingTransferDecision>>>,
    active_transfer_cancels: AsyncMutex<HashMap<String, Arc<AtomicBool>>>,
    active_transfer_pauses: AsyncMutex<HashMap<String, Arc<AtomicBool>>>,
    parallel_incoming_sessions: AsyncMutex<HashMap<String, Arc<ParallelIncomingTransferSession>>>,
    retryable_outgoing: AsyncMutex<HashMap<String, RetryableOutgoingTransfer>>,
    remote_server_running: AtomicBool,
    remote_access_status: RwLock<RemoteAccessStatus>,
}

#[derive(Debug, Clone)]
struct RetryableOutgoingTransfer {
    peer_device_id: String,
    pair_code: Option<String>,
    draft: SendDraft,
}

pub struct ParallelIncomingTransferSession {
    pub transfer_id: String,
    pub destination_root: PathBuf,
    pub total_bytes: u64,
    pub completed_bytes: AtomicU64,
    pub started_at_ms: u64,
    pub cancel_flag: Arc<AtomicBool>,
    pub pause_flag: Arc<AtomicBool>,
    pub active_files: AsyncMutex<HashMap<u16, String>>,
    remaining_workers: AtomicUsize,
    notify: Notify,
    error: AsyncMutex<Option<String>>,
}

impl ParallelIncomingTransferSession {
    pub fn new(
        transfer_id: String,
        destination_root: PathBuf,
        total_bytes: u64,
        initial_completed_bytes: u64,
        worker_count: usize,
        cancel_flag: Arc<AtomicBool>,
        pause_flag: Arc<AtomicBool>,
    ) -> Self {
        Self {
            transfer_id,
            destination_root,
            total_bytes,
            completed_bytes: AtomicU64::new(initial_completed_bytes),
            started_at_ms: now_ms(),
            cancel_flag,
            pause_flag,
            active_files: AsyncMutex::new(HashMap::new()),
            remaining_workers: AtomicUsize::new(worker_count),
            notify: Notify::new(),
            error: AsyncMutex::new(None),
        }
    }

    pub async fn set_error(&self, error: String) {
        let mut slot = self.error.lock().await;
        // An explicit peer cancellation takes precedence over workers closing
        // their sockets, which may be observed on another task first.
        if slot.is_none() || error == transfer::error::PEER_CANCELED {
            *slot = Some(error);
        }
        self.notify.notify_waiters();
    }

    pub async fn error(&self) -> Option<String> {
        self.error.lock().await.clone()
    }

    pub fn mark_worker_finished(&self) {
        if self.remaining_workers.fetch_sub(1, Ordering::SeqCst) <= 1 {
            self.notify.notify_waiters();
        }
    }

    pub async fn wait_finished(&self) -> Result<(), String> {
        loop {
            let notified = self.notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if let Some(error) = self.error.lock().await.clone() {
                return Err(error);
            }
            if self.remaining_workers.load(Ordering::SeqCst) == 0 {
                return Ok(());
            }
            notified.await;
        }
    }
}

impl AppState {
    pub fn new() -> Self {
        let mut settings = AppSettings::default();
        if let Ok(device_name) = env::var("COMPUTERNAME") {
            if !device_name.trim().is_empty() {
                settings.device_name = device_name;
            }
        }
        settings.pair_code = generate_pair_code();

        let local_adapters =
            apply_adapter_priority_order(discovery::adapter::enumerate_local_adapters(), &settings);

        Self {
            inner: Arc::new(AppStateInner {
                settings: RwLock::new(settings),
                local_adapters: RwLock::new(local_adapters),
                peers: RwLock::new(Vec::new()),
                incoming_offer: RwLock::new(None),
                send_draft: RwLock::new(None),
                transfers: RwLock::new(Vec::new()),
                launched_at_ms: now_ms(),
                device_id: Uuid::new_v4().to_string(),
                instance_id: Uuid::new_v4().to_string(),
                persistence_path: RwLock::new(None),
                persistence_write_lock: AsyncMutex::new(()),
                discovery_tx: Mutex::new(None),
                pending_incoming: AsyncMutex::new(HashMap::new()),
                active_transfer_cancels: AsyncMutex::new(HashMap::new()),
                active_transfer_pauses: AsyncMutex::new(HashMap::new()),
                parallel_incoming_sessions: AsyncMutex::new(HashMap::new()),
                retryable_outgoing: AsyncMutex::new(HashMap::new()),
                remote_server_running: AtomicBool::new(false),
                remote_access_status: RwLock::new(RemoteAccessStatus::disabled()),
            }),
        }
    }

    pub async fn initialize_persistence(&self) -> Result<(), String> {
        let path = persistence::resolve_persistence_path();
        *self.inner.persistence_path.write().await = Some(path.clone());

        if let Some(snapshot) = task::spawn_blocking(move || persistence::load_state(&path))
            .await
            .map_err(|error| error.to_string())??
        {
            let mut settings = snapshot.settings;
            if settings.device_name.trim().is_empty() {
                settings.device_name = default_device_name();
            }
            if settings.pair_code.trim().len() != 6 {
                settings.pair_code = generate_pair_code();
            }
            let adapters = apply_adapter_priority_order(
                self.inner.local_adapters.read().await.clone(),
                &settings,
            );

            *self.inner.settings.write().await = settings;
            *self.inner.local_adapters.write().await = adapters;
            *self.inner.transfers.write().await =
                persistence::normalize_transfers_from_disk(snapshot.transfers);
        }

        Ok(())
    }

    pub fn start_discovery(&self, app: tauri::AppHandle) {
        let (tx, rx) = mpsc::unbounded_channel();

        if let Ok(mut slot) = self.inner.discovery_tx.lock() {
            *slot = Some(tx.clone());
        }

        let state = self.clone();
        tauri::async_runtime::spawn(async move {
            discovery::service::run(state, app, rx).await;
        });

        let _ = tx.send(discovery::service::DiscoveryCommand::ProbeNow);
    }

    pub fn start_control_server(&self, app: tauri::AppHandle) {
        let state = self.clone();
        tauri::async_runtime::spawn(async move {
            transfer::control::run_listener(state, app).await;
        });
    }

    pub fn start_remote_server(&self, app: tauri::AppHandle) {
        let state = self.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(error) = crate::remote::ensure_listener(state.clone(), app.clone()).await {
                warn!("remote listener did not start: {error}");
                let settings = state.set_remote_access_enabled(false).await;
                state
                    .set_remote_access_status(crate::types::RemoteAccessStatus::disabled())
                    .await;
                let _ = crate::events::emit_settings_updated(&app, &settings);
            }
        });
    }

    pub async fn bootstrap(&self) -> AppBootstrap {
        AppBootstrap {
            app_name: "LanDrop".to_string(),
            phase_label: "Ready".to_string(),
            phase_completed: 0,
            phase_total: 0,
            launched_at_ms: self.inner.launched_at_ms,
            settings: self.inner.settings.read().await.clone(),
            local_adapters: self.inner.local_adapters.read().await.clone(),
            peers: self.inner.peers.read().await.clone(),
            incoming_offer: self.inner.incoming_offer.read().await.clone(),
            send_draft: self.inner.send_draft.read().await.clone(),
            transfers: self.inner.transfers.read().await.clone(),
            remote_access_status: self.inner.remote_access_status.read().await.clone(),
        }
    }

    pub async fn refresh_discovery(&self) -> AppBootstrap {
        self.refresh_local_adapters().await;
        self.send_discovery_command(discovery::service::DiscoveryCommand::ProbeNow);
        self.bootstrap().await
    }

    pub async fn set_receive_enabled(&self, enabled: bool) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.receive_enabled = enabled;
        let next = settings.clone();
        drop(settings);
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn set_remote_access_enabled(&self, enabled: bool) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.remote_access_enabled = enabled;
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn set_remote_auto_port_mapping(&self, enabled: bool) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.remote_auto_port_mapping = enabled;
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub fn try_mark_remote_server_running(&self) -> bool {
        self.inner
            .remote_server_running
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
    }

    pub fn mark_remote_server_stopped(&self) {
        self.inner
            .remote_server_running
            .store(false, Ordering::SeqCst);
    }

    pub fn is_remote_server_running(&self) -> bool {
        self.inner.remote_server_running.load(Ordering::SeqCst)
    }

    pub async fn set_remote_access_status(&self, status: RemoteAccessStatus) {
        *self.inner.remote_access_status.write().await = status;
    }

    pub async fn remote_access_status(&self) -> RemoteAccessStatus {
        self.inner.remote_access_status.read().await.clone()
    }

    pub async fn set_device_name(&self, name: String) -> Result<AppSettings, String> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err("Device name cannot be empty.".to_string());
        }
        if trimmed.chars().count() > 48 {
            return Err("Device name must stay under 48 characters.".to_string());
        }

        let mut settings = self.inner.settings.write().await;
        settings.device_name = trimmed.to_string();
        let next = settings.clone();
        drop(settings);
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        Ok(next)
    }

    pub async fn set_require_pair_code(&self, enabled: bool) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.require_pair_code = enabled;
        if settings.pair_code.trim().len() != 6 {
            settings.pair_code = generate_pair_code();
        }
        let next = settings.clone();
        drop(settings);
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn regenerate_pair_code(&self) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.pair_code = generate_pair_code();
        let next = settings.clone();
        drop(settings);
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn set_quick_save_mode(&self, mode: QuickSaveMode) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.quick_save_mode = mode;
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn trust_device(&self, device_id: &str, name: &str, ip: &str) {
        let mut settings = self.inner.settings.write().await;
        if !settings.trusted_device_ids.iter().any(|id| id == device_id) {
            settings.trusted_device_ids.push(device_id.to_string());
        }
        let trusted = TrustedDevice {
            device_id: device_id.to_string(),
            name: name.trim().to_string(),
            ip: ip.to_string(),
            last_seen_ms: now_ms(),
        };
        if let Some(existing) = settings
            .trusted_devices
            .iter_mut()
            .find(|device| device.device_id == device_id)
        {
            *existing = trusted;
        } else {
            settings.trusted_devices.push(trusted);
        }
        drop(settings);
        self.persist_state_background();
    }

    pub async fn untrust_device(&self, device_id: &str) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.trusted_device_ids.retain(|id| id != device_id);
        settings
            .trusted_devices
            .retain(|device| device.device_id != device_id);
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn clear_trusted_devices(&self) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.trusted_device_ids.clear();
        settings.trusted_devices.clear();
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn set_theme(&self, theme: ThemePreference) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.theme = theme;
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn set_concurrent_file_streams(&self, count: u16) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.concurrent_file_streams = clamp_concurrent_file_streams(count);
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn set_default_receive_folder(&self, folder: Option<String>) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.default_receive_folder = folder
            .map(|path| path.trim().to_string())
            .filter(|path| !path.is_empty());
        let next = settings.clone();
        drop(settings);
        self.persist_state_background();
        next
    }

    pub async fn set_adapter_priority_order(&self, adapter_ids: Vec<String>) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.adapter_priority_order = adapter_ids
            .into_iter()
            .filter(|id| !id.trim().is_empty())
            .collect();
        let next = settings.clone();
        let adapters =
            apply_adapter_priority_order(self.inner.local_adapters.read().await.clone(), &next);
        drop(settings);
        *self.inner.local_adapters.write().await = adapters;
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn set_disabled_adapter_ids(&self, adapter_ids: Vec<String>) -> AppSettings {
        let mut settings = self.inner.settings.write().await;
        settings.disabled_adapter_ids = adapter_ids
            .into_iter()
            .filter(|id| !id.trim().is_empty())
            .collect();
        let next = settings.clone();
        let adapters =
            apply_adapter_priority_order(self.inner.local_adapters.read().await.clone(), &next);
        drop(settings);
        *self.inner.local_adapters.write().await = adapters;
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn set_device_kind(&self, device_kind: String) -> AppSettings {
        let normalized = match device_kind.as_str() {
            "desktop" | "laptop" | "phone" | "tablet" | "android" => device_kind,
            _ => "desktop".to_string(),
        };
        let mut settings = self.inner.settings.write().await;
        settings.device_kind = normalized;
        let next = settings.clone();
        drop(settings);
        self.send_discovery_command(discovery::service::DiscoveryCommand::AnnounceNow);
        self.persist_state_background();
        next
    }

    pub async fn refresh_local_adapters(&self) -> Vec<LocalAdapter> {
        let settings = self.inner.settings.read().await.clone();
        let adapters =
            apply_adapter_priority_order(discovery::adapter::enumerate_local_adapters(), &settings);
        *self.inner.local_adapters.write().await = adapters.clone();
        adapters
    }

    pub async fn discovery_identity(&self) -> discovery::protocol::DiscoveryIdentity {
        let settings = self.inner.settings.read().await.clone();
        let local_adapters = self.inner.local_adapters.read().await.clone();

        discovery::protocol::DiscoveryIdentity {
            device_id: self.inner.device_id.clone(),
            instance_id: self.inner.instance_id.clone(),
            device_name: settings.device_name,
            device_kind: settings.device_kind,
            tcp_port: settings.tcp_port,
            availability: if settings.receive_enabled {
                crate::types::Availability::Available
            } else {
                crate::types::Availability::ReceiveDisabled
            },
            requires_pair_code: settings.require_pair_code,
            addresses: local_adapters
                .into_iter()
                .filter(|adapter| adapter.status == crate::types::LocalAdapterStatus::Active)
                .flat_map(|adapter| {
                    let priority = adapter.priority;
                    let kind = adapter.adapter_kind;
                    adapter.ipv4_addresses.into_iter().map(move |ip| {
                        discovery::protocol::AdvertisedAddress {
                            ip,
                            adapter_kind: kind,
                            priority,
                        }
                    })
                })
                .collect(),
        }
    }

    pub async fn upsert_peer(&self, peer: PeerDevice) -> bool {
        let mut peers = self.inner.peers.write().await;

        if let Some(existing) = peers
            .iter_mut()
            .find(|item| item.device_id == peer.device_id)
        {
            let changed = existing.instance_id != peer.instance_id
                || existing.name != peer.name
                || existing.device_kind != peer.device_kind
                || existing.ip != peer.ip
                || existing.port != peer.port
                || existing.adapter_kind != peer.adapter_kind
                || existing.availability != peer.availability
                || existing.requires_pair_code != peer.requires_pair_code
                || existing.priority != peer.priority;

            existing.instance_id = peer.instance_id;
            existing.name = peer.name;
            existing.device_kind = peer.device_kind;
            existing.ip = peer.ip;
            existing.port = peer.port;
            existing.adapter_kind = peer.adapter_kind;
            existing.availability = peer.availability;
            existing.requires_pair_code = peer.requires_pair_code;
            existing.last_seen_ms = peer.last_seen_ms;
            existing.priority = peer.priority;
            sort_peers(&mut peers);
            return changed;
        }

        peers.push(peer);
        sort_peers(&mut peers);
        true
    }

    pub async fn prune_stale_peers(&self, stale_after_ms: u64) -> bool {
        let mut peers = self.inner.peers.write().await;
        let now = now_ms();
        let before = peers.len();
        peers.retain(|peer| {
            (peer.adapter_kind == crate::discovery::adapter::AdapterKind::Unknown
                && peer.priority >= 30)
                || now.saturating_sub(peer.last_seen_ms) <= stale_after_ms
        });
        before != peers.len()
    }

    pub async fn peers(&self) -> Vec<PeerDevice> {
        self.inner.peers.read().await.clone()
    }

    pub async fn peer_by_id(&self, device_id: &str) -> Option<PeerDevice> {
        self.inner
            .peers
            .read()
            .await
            .iter()
            .find(|peer| peer.device_id == device_id)
            .cloned()
    }

    pub async fn stage_send_paths(&self, paths: Vec<PathBuf>) -> Result<SendDraft, String> {
        let draft = task::spawn_blocking(move || transfer::scanner::build_send_draft(paths))
            .await
            .map_err(|error| error.to_string())??;

        *self.inner.send_draft.write().await = Some(draft.clone());
        Ok(draft)
    }

    pub async fn send_draft(&self) -> Option<SendDraft> {
        self.inner.send_draft.read().await.clone()
    }

    pub async fn clear_send_draft(&self) {
        *self.inner.send_draft.write().await = None;
    }

    pub async fn add_or_replace_manual_peer(&self, peer: PeerDevice) {
        let mut peers = self.inner.peers.write().await;
        peers.retain(|existing| existing.device_id != peer.device_id);
        peers.insert(0, peer);
        sort_peers(&mut peers);
    }

    pub async fn set_incoming_offer(&self, offer: Option<IncomingTransferOffer>) {
        *self.inner.incoming_offer.write().await = offer;
    }

    pub async fn clear_incoming_offer(&self, transfer_id: &str) -> bool {
        let mut offer = self.inner.incoming_offer.write().await;
        if offer
            .as_ref()
            .is_some_and(|offer| offer.transfer_id == transfer_id)
        {
            *offer = None;
            true
        } else {
            false
        }
    }

    pub async fn register_pending_incoming(
        &self,
        transfer_id: String,
    ) -> oneshot::Receiver<IncomingTransferDecision> {
        let (tx, rx) = oneshot::channel();
        self.inner
            .pending_incoming
            .lock()
            .await
            .insert(transfer_id, tx);
        rx
    }

    pub async fn respond_to_pending_incoming(
        &self,
        transfer_id: &str,
        decision: IncomingTransferDecision,
    ) -> Result<(), String> {
        let sender = self
            .inner
            .pending_incoming
            .lock()
            .await
            .remove(transfer_id)
            .ok_or_else(|| "incoming offer is no longer pending".to_string())?;

        sender
            .send(decision)
            .map_err(|_| "failed to deliver the incoming response".to_string())
    }

    pub async fn cancel_pending_incoming(&self, transfer_id: &str) {
        self.inner.pending_incoming.lock().await.remove(transfer_id);
    }

    pub async fn upsert_transfer(&self, summary: TransferSummary) {
        let mut transfers = self.inner.transfers.write().await;
        if let Some(index) = transfers
            .iter()
            .position(|item| item.transfer_id == summary.transfer_id)
        {
            transfers[index] = summary;
        } else {
            transfers.insert(0, summary);
        }
        drop(transfers);
        self.persist_state_background();
    }

    pub async fn set_transfer_status(
        &self,
        transfer_id: &str,
        status: TransferStatus,
        current_file: Option<String>,
    ) {
        let mut transfers = self.inner.transfers.write().await;
        if let Some(summary) = transfers
            .iter_mut()
            .find(|item| item.transfer_id == transfer_id)
        {
            let is_terminal = matches!(
                summary.status,
                TransferStatus::Completed
                    | TransferStatus::Failed
                    | TransferStatus::Rejected
                    | TransferStatus::Canceled
            );
            let next_is_active = matches!(
                status,
                TransferStatus::Transferring
                    | TransferStatus::Paused
                    | TransferStatus::Accepted
                    | TransferStatus::AwaitingAccept
                    | TransferStatus::Scanning
                    | TransferStatus::Idle
            );
            // Don't let late progress/pause updates resurrect a finished transfer.
            if is_terminal && next_is_active {
                return;
            }
            // Cancel wins over late worker errors and completion/receipt races.
            if matches!(summary.status, TransferStatus::Canceled)
                && !matches!(status, TransferStatus::Canceled)
            {
                return;
            }
            summary.status = status;
            summary.current_file = current_file;
            if !next_is_active {
                summary.speed_mbps = None;
                summary.eta_seconds = None;
            }
        }
        drop(transfers);
        self.persist_state_background();
    }

    pub async fn update_transfer_progress(
        &self,
        transfer_id: &str,
        completed_bytes: u64,
        current_file: Option<String>,
        speed_mbps: Option<f32>,
        eta_seconds: Option<u64>,
    ) {
        let mut transfers = self.inner.transfers.write().await;
        if let Some(summary) = transfers
            .iter_mut()
            .find(|item| item.transfer_id == transfer_id)
        {
            if matches!(
                summary.status,
                TransferStatus::Completed
                    | TransferStatus::Failed
                    | TransferStatus::Rejected
                    | TransferStatus::Canceled
            ) {
                return;
            }
            summary.completed_bytes = completed_bytes;
            summary.progress_pct = if summary.total_bytes == 0 {
                100.0
            } else {
                (((completed_bytes as f64 / summary.total_bytes as f64) * 100.0).min(100.0)) as f32
            };
            summary.current_file = current_file;
            summary.speed_mbps = speed_mbps;
            summary.eta_seconds = eta_seconds;
        }
    }

    pub async fn clear_transfer_history(&self) {
        self.inner.transfers.write().await.retain(|transfer| {
            !matches!(
                transfer.status,
                TransferStatus::Completed
                    | TransferStatus::Failed
                    | TransferStatus::Rejected
                    | TransferStatus::Canceled
            )
        });
        self.persist_state_background();
    }

    pub async fn delete_transfer_history_item(&self, transfer_id: &str) {
        self.inner
            .transfers
            .write()
            .await
            .retain(|transfer| transfer.transfer_id != transfer_id);
        self.inner
            .retryable_outgoing
            .lock()
            .await
            .remove(transfer_id);
        self.persist_state_background();
    }

    pub async fn register_outgoing_transfer(
        &self,
        transfer_id: String,
        peer_device_id: String,
        pair_code: Option<String>,
        draft: SendDraft,
    ) -> (Arc<AtomicBool>, Arc<AtomicBool>) {
        let cancel_flag = Arc::new(AtomicBool::new(false));
        let pause_flag = Arc::new(AtomicBool::new(false));
        self.inner
            .active_transfer_cancels
            .lock()
            .await
            .insert(transfer_id.clone(), cancel_flag.clone());
        self.inner
            .active_transfer_pauses
            .lock()
            .await
            .insert(transfer_id.clone(), pause_flag.clone());
        self.inner.retryable_outgoing.lock().await.insert(
            transfer_id,
            RetryableOutgoingTransfer {
                peer_device_id,
                pair_code,
                draft,
            },
        );
        (cancel_flag, pause_flag)
    }

    pub async fn register_incoming_transfer(
        &self,
        transfer_id: String,
    ) -> (Arc<AtomicBool>, Arc<AtomicBool>) {
        let cancel_flag = Arc::new(AtomicBool::new(false));
        let pause_flag = Arc::new(AtomicBool::new(false));
        self.inner
            .active_transfer_cancels
            .lock()
            .await
            .insert(transfer_id.clone(), cancel_flag.clone());
        self.inner
            .active_transfer_pauses
            .lock()
            .await
            .insert(transfer_id, pause_flag.clone());
        (cancel_flag, pause_flag)
    }

    pub async fn finish_transfer(&self, transfer_id: &str) {
        self.inner
            .active_transfer_cancels
            .lock()
            .await
            .remove(transfer_id);
        self.inner
            .active_transfer_pauses
            .lock()
            .await
            .remove(transfer_id);
        self.inner
            .parallel_incoming_sessions
            .lock()
            .await
            .remove(transfer_id);
        self.persist_state_background();
    }

    pub async fn register_parallel_incoming_session(
        &self,
        transfer_id: String,
        session: Arc<ParallelIncomingTransferSession>,
    ) {
        self.inner
            .parallel_incoming_sessions
            .lock()
            .await
            .insert(transfer_id, session);
    }

    pub async fn parallel_incoming_session(
        &self,
        transfer_id: &str,
    ) -> Option<Arc<ParallelIncomingTransferSession>> {
        self.inner
            .parallel_incoming_sessions
            .lock()
            .await
            .get(transfer_id)
            .cloned()
    }

    pub async fn cancel_transfer(&self, transfer_id: &str) -> Result<(), String> {
        let flag = self
            .inner
            .active_transfer_cancels
            .lock()
            .await
            .get(transfer_id)
            .cloned()
            .ok_or_else(|| "transfer is not currently active".to_string())?;
        flag.store(true, Ordering::SeqCst);
        Ok(())
    }

    pub async fn set_transfer_paused(&self, transfer_id: &str, paused: bool) -> Result<(), String> {
        let flag = self
            .inner
            .active_transfer_pauses
            .lock()
            .await
            .get(transfer_id)
            .cloned()
            .ok_or_else(|| "transfer is not currently active".to_string())?;
        flag.store(paused, Ordering::SeqCst);
        Ok(())
    }

    pub async fn retry_context(
        &self,
        transfer_id: &str,
    ) -> Option<(String, Option<String>, SendDraft)> {
        self.inner
            .retryable_outgoing
            .lock()
            .await
            .get(transfer_id)
            .cloned()
            .map(|value| (value.peer_device_id, value.pair_code, value.draft))
    }

    pub async fn transfers(&self) -> Vec<TransferSummary> {
        self.inner.transfers.read().await.clone()
    }

    pub async fn persistence_path(&self) -> Option<PathBuf> {
        self.inner.persistence_path.read().await.clone()
    }

    pub fn send_discovery_command(&self, command: discovery::service::DiscoveryCommand) {
        if let Ok(slot) = self.inner.discovery_tx.lock() {
            if let Some(tx) = slot.as_ref() {
                let _ = tx.send(command);
            }
        }
    }

    fn persist_state_background(&self) {
        let state = self.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(error) = state.persist_state_snapshot().await {
                warn!("failed to persist app state: {error}");
            }
        });
    }

    async fn persist_state_snapshot(&self) -> Result<(), String> {
        // Status and cleanup both request saves. Serialize the snapshot reads
        // and writes so their shared temporary file cannot collide or restore
        // an older awaiting/active status over the cancellation.
        let _write_guard = self.inner.persistence_write_lock.lock().await;
        let path = self
            .inner
            .persistence_path
            .read()
            .await
            .clone()
            .ok_or_else(|| "persistence path is not initialized".to_string())?;

        let settings = self.inner.settings.read().await.clone();
        let transfers = self.inner.transfers.read().await.clone();
        let snapshot = persistence::PersistedAppState {
            settings,
            transfers: persistence::prepare_transfers_for_persistence(&transfers),
        };

        task::spawn_blocking(move || persistence::save_state(&path, &snapshot))
            .await
            .map_err(|error| error.to_string())?
    }
}

fn sort_peers(peers: &mut [PeerDevice]) {
    peers.sort_by(|left, right| {
        right
            .priority
            .cmp(&left.priority)
            .then_with(|| left.name.cmp(&right.name))
    });
}

fn apply_adapter_priority_order(
    mut adapters: Vec<LocalAdapter>,
    settings: &AppSettings,
) -> Vec<LocalAdapter> {
    let order = &settings.adapter_priority_order;
    let disabled = &settings.disabled_adapter_ids;

    for adapter in &mut adapters {
        if disabled.iter().any(|id| id == &adapter.id) {
            adapter.status = crate::types::LocalAdapterStatus::Ignored;
            adapter.status_reason = Some("disabled in LanDrop settings".to_string());
        }
        if let Some(index) = order.iter().position(|id| id == &adapter.id) {
            adapter.priority = 10_000_i32.saturating_sub(index as i32);
        }
    }

    adapters.sort_by(|left, right| {
        right
            .priority
            .cmp(&left.priority)
            .then_with(|| left.friendly_name.cmp(&right.friendly_name))
    });

    let preferred_index = adapters
        .iter()
        .position(|candidate| candidate.status == crate::types::LocalAdapterStatus::Active);

    for (index, adapter) in adapters.iter_mut().enumerate() {
        adapter.is_preferred = adapter.status == crate::types::LocalAdapterStatus::Active
            && Some(index) == preferred_index;
    }

    adapters
}

fn generate_pair_code() -> String {
    let value = (Uuid::new_v4().as_u128() % 1_000_000) as u32;
    format!("{value:06}")
}

fn default_device_name() -> String {
    if let Ok(device_name) = env::var("COMPUTERNAME") {
        if !device_name.trim().is_empty() {
            return device_name;
        }
    }

    "This PC".to_string()
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    use crate::transfer::error::PEER_CANCELED;
    use crate::types::TransferDirection;

    #[tokio::test]
    async fn concurrent_history_saves_keep_the_canceled_status() {
        let state = AppState::new();
        let folder = std::env::temp_dir().join(format!("landrop-cancel-test-{}", Uuid::new_v4()));
        let path = folder.join("state.json");
        *state.inner.persistence_path.write().await = Some(path.clone());
        state.inner.transfers.write().await.push(TransferSummary {
            transfer_id: "test-save".into(),
            peer_name: "Phone".into(),
            direction: TransferDirection::Incoming,
            status: TransferStatus::Canceled,
            file_count: 1,
            total_bytes: 1600,
            completed_bytes: 1600,
            progress_pct: 100.0,
            current_file: Some(PEER_CANCELED.into()),
            speed_mbps: None,
            eta_seconds: None,
        });
        let mut tasks = vec![];
        for _ in 0..20 {
            let task_state = state.clone();
            tasks.push(tokio::spawn(async move {
                task_state.persist_state_snapshot().await
            }));
        }
        for task in tasks {
            task.await.unwrap().unwrap();
        }
        let saved = persistence::load_state(&path).unwrap().unwrap();
        assert!(matches!(
            saved.transfers[0].status,
            TransferStatus::Canceled
        ));
        assert_eq!(
            saved.transfers[0].current_file.as_deref(),
            Some(PEER_CANCELED)
        );
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(folder).unwrap();
    }

    #[tokio::test]
    async fn cancel_message_survives_late_progress_errors_and_completion() {
        let state = AppState::new();
        state
            .upsert_transfer(TransferSummary {
                transfer_id: "test-cancel".into(),
                peer_name: "Phone".into(),
                direction: TransferDirection::Incoming,
                status: TransferStatus::Transferring,
                file_count: 6,
                total_bytes: 1600,
                completed_bytes: 1600,
                progress_pct: 100.0,
                current_file: Some("photo.jpg".into()),
                speed_mbps: Some(10.33),
                eta_seconds: Some(0),
            })
            .await;
        state
            .set_transfer_status(
                "test-cancel",
                TransferStatus::Canceled,
                Some(PEER_CANCELED.into()),
            )
            .await;
        state
            .update_transfer_progress(
                "test-cancel",
                1600,
                Some("photo.jpg".into()),
                Some(20.0),
                Some(0),
            )
            .await;
        state
            .set_transfer_status(
                "test-cancel",
                TransferStatus::Failed,
                Some("early eof".into()),
            )
            .await;
        state
            .set_transfer_status(
                "test-cancel",
                TransferStatus::Completed,
                Some("Saved".into()),
            )
            .await;
        let transfer = state.transfers().await.remove(0);
        assert!(matches!(transfer.status, TransferStatus::Canceled));
        assert_eq!(transfer.current_file.as_deref(), Some(PEER_CANCELED));
        assert_eq!(transfer.completed_bytes, 1600);
        assert!(transfer.speed_mbps.is_none());
        assert!(transfer.eta_seconds.is_none());
    }

    #[tokio::test]
    async fn canceled_offer_cannot_be_accepted_and_cannot_clear_a_newer_offer() {
        let state = AppState::new();
        let _decision_rx = state.register_pending_incoming("old".into()).await;
        state.cancel_pending_incoming("old").await;
        assert!(state
            .respond_to_pending_incoming(
                "old",
                IncomingTransferDecision {
                    accept: true,
                    save_path: Some("unused".into()),
                }
            )
            .await
            .is_err());
        state
            .set_incoming_offer(Some(IncomingTransferOffer {
                transfer_id: "new".into(),
                sender_device_id: "phone".into(),
                sender_name: "Phone".into(),
                file_count: 1,
                directory_count: 0,
                total_bytes: 1,
                requires_pair_code: false,
                top_level_items: vec![],
            }))
            .await;
        assert!(!state.clear_incoming_offer("old").await);
        assert_eq!(
            state.bootstrap().await.incoming_offer.unwrap().transfer_id,
            "new"
        );
        assert!(state.clear_incoming_offer("new").await);
        assert!(state.bootstrap().await.incoming_offer.is_none());
    }
}
