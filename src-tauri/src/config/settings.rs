use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThemePreference {
    System,
    Light,
    Dark,
    Oled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum QuickSaveMode {
    Off,
    Favorites,
    On,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TrustedDevice {
    pub device_id: String,
    pub name: String,
    pub ip: String,
    pub last_seen_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AppSettings {
    pub device_name: String,
    pub device_kind: String,
    pub receive_enabled: bool,
    pub require_pair_code: bool,
    pub pair_code: String,
    pub quick_save_mode: QuickSaveMode,
    pub trusted_device_ids: Vec<String>,
    pub trusted_devices: Vec<TrustedDevice>,
    pub default_receive_folder: Option<String>,
    pub adapter_priority_order: Vec<String>,
    pub disabled_adapter_ids: Vec<String>,
    pub prefer_ethernet: bool,
    pub allow_wifi: bool,
    pub allow_apipa: bool,
    pub concurrent_file_streams: u16,
    pub discovery_port: u16,
    pub tcp_port: u16,
    pub remote_access_enabled: bool,
    pub remote_auto_port_mapping: bool,
    pub remote_listen_port: u16,
    pub theme: ThemePreference,
}

pub const MIN_CONCURRENT_FILE_STREAMS: u16 = 1;
pub const MAX_CONCURRENT_FILE_STREAMS: u16 = 100;
pub const DEFAULT_CONCURRENT_FILE_STREAMS: u16 = 4;

pub fn clamp_concurrent_file_streams(value: u16) -> u16 {
    value.clamp(MIN_CONCURRENT_FILE_STREAMS, MAX_CONCURRENT_FILE_STREAMS)
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            device_name: "This PC".to_string(),
            device_kind: "desktop".to_string(),
            receive_enabled: true,
            require_pair_code: false,
            pair_code: "000000".to_string(),
            quick_save_mode: QuickSaveMode::Off,
            trusted_device_ids: Vec::new(),
            trusted_devices: Vec::new(),
            default_receive_folder: None,
            adapter_priority_order: Vec::new(),
            disabled_adapter_ids: Vec::new(),
            prefer_ethernet: true,
            allow_wifi: true,
            allow_apipa: true,
            concurrent_file_streams: DEFAULT_CONCURRENT_FILE_STREAMS,
            discovery_port: 50500,
            tcp_port: 50501,
            remote_access_enabled: false,
            remote_auto_port_mapping: true,
            remote_listen_port: 50502,
            theme: ThemePreference::System,
        }
    }
}
