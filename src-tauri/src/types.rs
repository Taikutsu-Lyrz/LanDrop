use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::config::settings::AppSettings;
use crate::discovery::adapter::AdapterKind;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Available,
    Busy,
    ReceiveDisabled,
    Transferring,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferDirection {
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferStatus {
    Idle,
    Scanning,
    AwaitingAccept,
    Accepted,
    Rejected,
    Canceled,
    Transferring,
    Paused,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LocalAdapterStatus {
    Active,
    Ignored,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerDevice {
    pub device_id: String,
    pub instance_id: String,
    pub name: String,
    pub device_kind: String,
    pub ip: String,
    pub port: u16,
    pub adapter_kind: AdapterKind,
    pub availability: Availability,
    pub requires_pair_code: bool,
    pub last_seen_ms: u64,
    pub priority: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalAdapter {
    pub id: String,
    pub friendly_name: String,
    pub description: String,
    pub adapter_kind: AdapterKind,
    pub if_type: String,
    pub status: LocalAdapterStatus,
    pub status_reason: Option<String>,
    pub priority: i32,
    pub is_preferred: bool,
    pub ipv4_addresses: Vec<String>,
    pub gateway_addresses: Vec<String>,
    pub dns_servers: Vec<String>,
    pub physical_address: Option<String>,
    pub receive_link_speed_mbps: Option<u64>,
    pub transmit_link_speed_mbps: Option<u64>,
    pub ipv4_metric: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferSummary {
    pub transfer_id: String,
    pub peer_name: String,
    pub direction: TransferDirection,
    pub status: TransferStatus,
    pub file_count: u32,
    pub total_bytes: u64,
    pub completed_bytes: u64,
    pub progress_pct: f32,
    pub current_file: Option<String>,
    pub speed_mbps: Option<f32>,
    pub eta_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferTopLevelItem {
    pub kind: String,
    pub name: String,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferManifestEntryKind {
    Directory,
    File,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferManifestEntry {
    pub relative_path: String,
    pub kind: TransferManifestEntryKind,
    pub size: Option<u64>,
    pub modified_at_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendDraft {
    pub root_count: u32,
    pub file_count: u32,
    pub directory_count: u32,
    pub total_bytes: u64,
    pub contains_folders: bool,
    pub top_level_items: Vec<TransferTopLevelItem>,
    pub manifest_entries: Vec<TransferManifestEntry>,
    pub source_paths: Vec<String>,
    pub warnings: Vec<String>,
    pub scanned_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IncomingTransferOffer {
    pub transfer_id: String,
    pub sender_device_id: String,
    pub sender_name: String,
    pub total_bytes: u64,
    pub file_count: u32,
    pub directory_count: u32,
    pub requires_pair_code: bool,
    pub top_level_items: Vec<TransferTopLevelItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppBootstrap {
    pub app_name: String,
    pub phase_label: String,
    pub phase_completed: u32,
    pub phase_total: u32,
    pub launched_at_ms: u64,
    pub settings: AppSettings,
    pub local_adapters: Vec<LocalAdapter>,
    pub peers: Vec<PeerDevice>,
    pub incoming_offer: Option<IncomingTransferOffer>,
    pub send_draft: Option<SendDraft>,
    pub transfers: Vec<TransferSummary>,
    pub remote_access_status: RemoteAccessStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RemoteAccessState {
    Disabled,
    Listening,
    Mapped,
    ManualRequired,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteAccessStatus {
    pub state: RemoteAccessState,
    pub message: String,
    pub public_host: Option<String>,
    pub external_port: Option<u16>,
    pub mapping_method: Option<String>,
}

impl RemoteAccessStatus {
    pub fn disabled() -> Self {
        Self {
            state: RemoteAccessState::Disabled,
            message: "Remote access is disabled.".to_string(),
            public_host: None,
            external_port: None,
            mapping_method: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformCapabilities {
    pub platform: String,
    pub can_pick_folders: bool,
    pub can_drag_drop_files: bool,
    pub can_configure_firewall: bool,
    pub supports_network_adapter_ordering: bool,
    pub supports_android_nsd: bool,
    pub supports_notifications: bool,
}

#[derive(Debug, Clone)]
pub struct IncomingTransferDecision {
    pub accept: bool,
    pub save_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FirewallRuleResult {
    pub success: bool,
    pub message: String,
    pub commands: Vec<String>,
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}
