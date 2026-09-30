use tauri::{AppHandle, Emitter};

use crate::config::settings::AppSettings;
use crate::types::{IncomingTransferOffer, PeerDevice, SendDraft, TransferSummary};

pub const DEVICES_UPDATED_EVENT: &str = "landrop://devices-updated";
pub const SETTINGS_UPDATED_EVENT: &str = "landrop://settings-updated";
pub const INCOMING_TRANSFER_EVENT: &str = "landrop://incoming-transfer";
pub const SEND_DRAFT_UPDATED_EVENT: &str = "landrop://send-draft-updated";
pub const TRANSFERS_UPDATED_EVENT: &str = "landrop://transfers-updated";

pub fn emit_devices_updated(app: &AppHandle, peers: &[PeerDevice]) -> Result<(), String> {
    app.emit(DEVICES_UPDATED_EVENT, peers.to_vec())
        .map_err(|error| error.to_string())
}

pub fn emit_settings_updated(app: &AppHandle, settings: &AppSettings) -> Result<(), String> {
    app.emit(SETTINGS_UPDATED_EVENT, settings.clone())
        .map_err(|error| error.to_string())
}

pub fn emit_incoming_transfer(
    app: &AppHandle,
    offer: &Option<IncomingTransferOffer>,
) -> Result<(), String> {
    app.emit(INCOMING_TRANSFER_EVENT, offer.clone())
        .map_err(|error| error.to_string())
}

pub fn emit_send_draft_updated(app: &AppHandle, draft: &Option<SendDraft>) -> Result<(), String> {
    app.emit(SEND_DRAFT_UPDATED_EVENT, draft.clone())
        .map_err(|error| error.to_string())
}

pub fn emit_transfers_updated(
    app: &AppHandle,
    transfers: &[TransferSummary],
) -> Result<(), String> {
    app.emit(TRANSFERS_UPDATED_EVENT, transfers.to_vec())
        .map_err(|error| error.to_string())
}
