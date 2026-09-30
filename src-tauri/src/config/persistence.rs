use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::config::settings::AppSettings;
use crate::types::{TransferStatus, TransferSummary};

const APP_FOLDER_NAME: &str = "LanDrop";
const STATE_FILE_NAME: &str = "state.json";
const MAX_PERSISTED_TRANSFERS: usize = 100;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersistedAppState {
    pub settings: AppSettings,
    pub transfers: Vec<TransferSummary>,
}

impl Default for PersistedAppState {
    fn default() -> Self {
        Self {
            settings: AppSettings::default(),
            transfers: Vec::new(),
        }
    }
}

pub fn resolve_persistence_path() -> PathBuf {
    if let Ok(app_data) = env::var("APPDATA") {
        return PathBuf::from(app_data)
            .join(APP_FOLDER_NAME)
            .join(STATE_FILE_NAME);
    }

    if let Ok(current_dir) = env::current_dir() {
        return current_dir.join(APP_FOLDER_NAME).join(STATE_FILE_NAME);
    }

    PathBuf::from(STATE_FILE_NAME)
}

pub fn load_state(path: &Path) -> Result<Option<PersistedAppState>, String> {
    if !path.exists() {
        return Ok(None);
    }

    let bytes = fs::read(path).map_err(|error| error.to_string())?;
    let state =
        serde_json::from_slice::<PersistedAppState>(&bytes).map_err(|error| error.to_string())?;
    Ok(Some(state))
}

pub fn save_state(path: &Path, state: &PersistedAppState) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }

    let json = serde_json::to_vec_pretty(state).map_err(|error| error.to_string())?;
    let temp_path = path.with_extension("tmp");
    fs::write(&temp_path, json).map_err(|error| error.to_string())?;

    if path.exists() {
        let _ = fs::remove_file(path);
    }

    fs::rename(temp_path, path).map_err(|error| error.to_string())
}

pub fn prepare_transfers_for_persistence(transfers: &[TransferSummary]) -> Vec<TransferSummary> {
    transfers
        .iter()
        .take(MAX_PERSISTED_TRANSFERS)
        .cloned()
        .collect()
}

pub fn normalize_transfers_from_disk(transfers: Vec<TransferSummary>) -> Vec<TransferSummary> {
    transfers
        .into_iter()
        .take(MAX_PERSISTED_TRANSFERS)
        .map(|mut transfer| {
            if matches!(
                transfer.status,
                TransferStatus::Idle
                    | TransferStatus::Scanning
                    | TransferStatus::AwaitingAccept
                    | TransferStatus::Accepted
                    | TransferStatus::Paused
                    | TransferStatus::Transferring
            ) {
                transfer.status = TransferStatus::Failed;
                transfer.current_file =
                    Some("Previous session ended before this transfer completed.".to_string());
                transfer.speed_mbps = None;
                transfer.eta_seconds = None;
            }
            transfer
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::normalize_transfers_from_disk;
    use crate::types::{TransferDirection, TransferStatus, TransferSummary};

    #[test]
    fn incomplete_transfers_are_marked_failed_on_load() {
        let transfers = vec![TransferSummary {
            transfer_id: "abc".to_string(),
            peer_name: "Peer".to_string(),
            direction: TransferDirection::Outgoing,
            status: TransferStatus::Transferring,
            file_count: 4,
            total_bytes: 1024,
            completed_bytes: 512,
            progress_pct: 50.0,
            current_file: Some("chunk.bin".to_string()),
            speed_mbps: Some(180.0),
            eta_seconds: Some(2),
        }];

        let restored = normalize_transfers_from_disk(transfers);
        assert_eq!(restored.len(), 1);
        assert!(matches!(restored[0].status, TransferStatus::Failed));
        assert_eq!(restored[0].speed_mbps, None);
        assert_eq!(restored[0].eta_seconds, None);
    }
}
