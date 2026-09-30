use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::types::{
    now_ms, IncomingTransferOffer, SendDraft, TransferManifestEntry, TransferTopLevelItem,
};

pub const CONTROL_PROTOCOL_VERSION: u16 = 1;
#[allow(dead_code)]
pub const FRAMED_PROTOCOL_VERSION: u16 = 2;
const MAX_CONTROL_FRAME: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ControlMessage {
    PeerHelloRequest(PeerHelloRequestMessage),
    PeerHelloResponse(PeerHelloResponseMessage),
    TransferOffer(TransferOfferMessage),
    TransferResponse(TransferResponseMessage),
    TransferManifest(TransferManifestMessage),
    TransferResumePlan(TransferResumePlanMessage),
    FileStart(FileStartMessage),
    FileChunk(FileChunkMessage),
    FileChunkAck(FileChunkAckMessage),
    FileEnd(FileEndMessage),
    ParallelWorkerOpen(ParallelWorkerOpenMessage),
    ParallelWorkerReady(ParallelWorkerReadyMessage),
    ParallelWorkerComplete(ParallelWorkerCompleteMessage),
    TransferFlowControl(TransferFlowControlMessage),
    TransferComplete(TransferCompleteMessage),
    TransferReceipt(TransferReceiptMessage),
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferEncoding {
    RawV1,
    FramedV2,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferOfferMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub sender_device_id: String,
    pub sender_name: String,
    pub receiver_device_id: Option<String>,
    pub pair_code: Option<String>,
    pub file_count: u32,
    pub directory_count: u32,
    pub total_bytes: u64,
    pub requires_pair_code: bool,
    pub supported_transfer_encodings: Option<Vec<TransferEncoding>>,
    #[serde(default = "default_max_parallel_file_streams")]
    pub max_parallel_file_streams: u16,
    pub top_level_items: Vec<TransferTopLevelItem>,
    pub sent_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferResponseMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub accepted: bool,
    pub message: Option<String>,
    pub selected_transfer_encoding: Option<TransferEncoding>,
    #[serde(default = "default_selected_parallel_file_streams")]
    pub selected_parallel_file_streams: u16,
    pub responded_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferManifestMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub total_bytes: u64,
    #[serde(default = "default_selected_parallel_file_streams")]
    pub selected_parallel_file_streams: u16,
    pub entries: Vec<TransferManifestEntry>,
    pub sent_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferResumePlanMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub files: Vec<TransferResumeFileState>,
    pub responded_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferResumeFileState {
    pub relative_path: String,
    pub completed_bytes: u64,
    pub complete: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileStartMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub relative_path: String,
    pub size: u64,
    pub offset: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileChunkMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub relative_path: String,
    pub offset: u64,
    pub length: u32,
    pub sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileChunkAckMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub relative_path: String,
    pub next_offset: u64,
    pub sequence: u64,
    pub acknowledged_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEndMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub relative_path: String,
    pub size: u64,
    pub completed_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParallelWorkerOpenMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub worker_id: u16,
    pub opened_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParallelWorkerReadyMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub worker_id: u16,
    pub accepted: bool,
    pub message: Option<String>,
    pub responded_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParallelWorkerCompleteMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub worker_id: u16,
    pub completed_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FlowControlState {
    Paused,
    Resumed,
    CancelRequested,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FlowControlReason {
    User,
    Backpressure,
    ReceiverBusy,
    Shutdown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferFlowControlMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub state: FlowControlState,
    pub reason: FlowControlReason,
    pub sent_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferCompleteMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub completed_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferReceiptMessage {
    pub protocol_version: u16,
    pub transfer_id: String,
    pub success: bool,
    pub message: Option<String>,
    pub saved_into: Option<String>,
    pub responded_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerHelloRequestMessage {
    pub protocol_version: u16,
    pub requested_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerHelloResponseMessage {
    pub protocol_version: u16,
    pub device_id: String,
    pub instance_id: String,
    pub device_name: String,
    #[serde(default = "default_device_kind")]
    pub device_kind: String,
    pub tcp_port: u16,
    pub availability: crate::types::Availability,
    pub requires_pair_code: bool,
    pub responded_at_ms: u64,
}

impl TransferOfferMessage {
    pub fn from_send_draft(
        transfer_id: String,
        sender_device_id: String,
        sender_name: String,
        receiver_device_id: Option<String>,
        pair_code: Option<String>,
        draft: &SendDraft,
        max_parallel_file_streams: u16,
    ) -> Self {
        let requires_pair_code = pair_code.is_some();
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            sender_device_id,
            sender_name,
            receiver_device_id,
            pair_code,
            file_count: draft.file_count,
            directory_count: draft.directory_count,
            total_bytes: draft.total_bytes,
            requires_pair_code,
            supported_transfer_encodings: Some(vec![
                TransferEncoding::FramedV2,
                TransferEncoding::RawV1,
            ]),
            max_parallel_file_streams,
            top_level_items: draft.top_level_items.clone(),
            sent_at_ms: now_ms(),
        }
    }

    pub fn into_incoming_offer(self) -> IncomingTransferOffer {
        IncomingTransferOffer {
            transfer_id: self.transfer_id,
            sender_device_id: self.sender_device_id,
            sender_name: self.sender_name,
            total_bytes: self.total_bytes,
            file_count: self.file_count,
            directory_count: self.directory_count,
            requires_pair_code: self.requires_pair_code,
            top_level_items: self.top_level_items,
        }
    }
}

impl TransferResponseMessage {
    #[allow(dead_code)]
    pub fn accepted(transfer_id: String, message: Option<String>) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            accepted: true,
            message,
            selected_transfer_encoding: Some(TransferEncoding::RawV1),
            selected_parallel_file_streams: 1,
            responded_at_ms: now_ms(),
        }
    }

    pub fn accepted_with_encoding(
        transfer_id: String,
        message: Option<String>,
        selected_transfer_encoding: TransferEncoding,
        selected_parallel_file_streams: u16,
    ) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            accepted: true,
            message,
            selected_transfer_encoding: Some(selected_transfer_encoding),
            selected_parallel_file_streams,
            responded_at_ms: now_ms(),
        }
    }

    pub fn rejected(transfer_id: String, message: Option<String>) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            accepted: false,
            message,
            selected_transfer_encoding: None,
            selected_parallel_file_streams: 1,
            responded_at_ms: now_ms(),
        }
    }
}

impl TransferManifestMessage {
    pub fn from_send_draft(
        transfer_id: String,
        draft: &SendDraft,
        selected_parallel_file_streams: u16,
    ) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            total_bytes: draft.total_bytes,
            selected_parallel_file_streams,
            entries: draft
                .manifest_entries
                .iter()
                .map(|entry| TransferManifestEntry {
                    relative_path: entry.relative_path.clone(),
                    kind: entry.kind.clone(),
                    size: entry.size,
                    modified_at_ms: entry.modified_at_ms,
                    source_path: None,
                })
                .collect(),
            sent_at_ms: now_ms(),
        }
    }
}

impl FileStartMessage {
    pub fn new(transfer_id: String, relative_path: String, size: u64, offset: u64) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            relative_path,
            size,
            offset,
        }
    }
}

impl FileChunkMessage {
    #[allow(dead_code)]
    pub fn new(
        transfer_id: String,
        relative_path: String,
        offset: u64,
        length: u32,
        sequence: u64,
    ) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            relative_path,
            offset,
            length,
            sequence,
        }
    }
}

impl FileChunkAckMessage {
    #[allow(dead_code)]
    pub fn new(
        transfer_id: String,
        relative_path: String,
        next_offset: u64,
        sequence: u64,
    ) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            relative_path,
            next_offset,
            sequence,
            acknowledged_at_ms: now_ms(),
        }
    }
}

impl FileEndMessage {
    #[allow(dead_code)]
    pub fn new(transfer_id: String, relative_path: String, size: u64) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            relative_path,
            size,
            completed_at_ms: now_ms(),
        }
    }
}

impl ParallelWorkerOpenMessage {
    pub fn new(transfer_id: String, worker_id: u16) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            worker_id,
            opened_at_ms: now_ms(),
        }
    }
}

impl ParallelWorkerReadyMessage {
    pub fn accepted(transfer_id: String, worker_id: u16, message: Option<String>) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            worker_id,
            accepted: true,
            message,
            responded_at_ms: now_ms(),
        }
    }

    pub fn rejected(transfer_id: String, worker_id: u16, message: Option<String>) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            worker_id,
            accepted: false,
            message,
            responded_at_ms: now_ms(),
        }
    }
}

impl ParallelWorkerCompleteMessage {
    pub fn new(transfer_id: String, worker_id: u16) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            worker_id,
            completed_at_ms: now_ms(),
        }
    }
}

impl TransferFlowControlMessage {
    #[allow(dead_code)]
    pub fn new(transfer_id: String, state: FlowControlState, reason: FlowControlReason) -> Self {
        Self {
            protocol_version: FRAMED_PROTOCOL_VERSION,
            transfer_id,
            state,
            reason,
            sent_at_ms: now_ms(),
        }
    }
}

impl TransferResumePlanMessage {
    pub fn new(transfer_id: String, files: Vec<TransferResumeFileState>) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            files,
            responded_at_ms: now_ms(),
        }
    }
}

impl TransferCompleteMessage {
    pub fn new(transfer_id: String) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            completed_at_ms: now_ms(),
        }
    }
}

impl TransferReceiptMessage {
    pub fn success(
        transfer_id: String,
        message: Option<String>,
        saved_into: Option<String>,
    ) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            success: true,
            message,
            saved_into,
            responded_at_ms: now_ms(),
        }
    }

    pub fn failure(transfer_id: String, message: Option<String>) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            transfer_id,
            success: false,
            message,
            saved_into: None,
            responded_at_ms: now_ms(),
        }
    }
}

impl PeerHelloRequestMessage {
    pub fn new() -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            requested_at_ms: now_ms(),
        }
    }
}

impl PeerHelloResponseMessage {
    pub fn new(
        device_id: String,
        instance_id: String,
        device_name: String,
        device_kind: String,
        tcp_port: u16,
        availability: crate::types::Availability,
        requires_pair_code: bool,
    ) -> Self {
        Self {
            protocol_version: CONTROL_PROTOCOL_VERSION,
            device_id,
            instance_id,
            device_name,
            device_kind,
            tcp_port,
            availability,
            requires_pair_code,
            responded_at_ms: now_ms(),
        }
    }
}

fn default_device_kind() -> String {
    "desktop".to_string()
}

fn default_max_parallel_file_streams() -> u16 {
    1
}

fn default_selected_parallel_file_streams() -> u16 {
    1
}

pub async fn write_message<W>(stream: &mut W, message: &ControlMessage) -> Result<(), String>
where
    W: AsyncWrite + Unpin + ?Sized,
{
    let payload = serde_json::to_vec(message).map_err(|error| error.to_string())?;
    if payload.len() > MAX_CONTROL_FRAME {
        return Err("control message too large".to_string());
    }

    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_all(&payload)
        .await
        .map_err(|error| error.to_string())?;
    stream.flush().await.map_err(|error| error.to_string())?;
    Ok(())
}

pub async fn read_message<R>(stream: &mut R) -> Result<ControlMessage, String>
where
    R: AsyncRead + Unpin + ?Sized,
{
    let mut length_buf = [0u8; 4];
    stream
        .read_exact(&mut length_buf)
        .await
        .map_err(|error| error.to_string())?;
    let length = u32::from_be_bytes(length_buf) as usize;
    if length > MAX_CONTROL_FRAME {
        return Err("control message too large".to_string());
    }

    let mut payload = vec![0u8; length];
    stream
        .read_exact(&mut payload)
        .await
        .map_err(|error| error.to_string())?;

    serde_json::from_slice(&payload).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::{read_message, write_message, ControlMessage, PeerHelloRequestMessage};
    use tokio::io::duplex;

    #[tokio::test]
    async fn control_frames_work_over_any_async_transport() {
        let (mut client, mut server) = duplex(4096);
        let writer = tokio::spawn(async move {
            write_message(
                &mut client,
                &ControlMessage::PeerHelloRequest(PeerHelloRequestMessage::new()),
            )
            .await
        });

        let message = read_message(&mut server)
            .await
            .expect("duplex transport should decode a control frame");
        writer
            .await
            .expect("writer task should finish")
            .expect("duplex transport should encode a control frame");

        assert!(matches!(message, ControlMessage::PeerHelloRequest(_)));
    }
}
