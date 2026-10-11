use std::collections::{HashMap, VecDeque};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use tokio::fs::{self, File};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, SeekFrom};
use tokio::net::TcpStream;
use tokio::sync::Mutex as AsyncMutex;
use tokio::time::{sleep, Duration};

use crate::app::{AppState, ParallelIncomingTransferSession};
use crate::events;
use crate::transfer::error::{PEER_CANCELED, USER_CANCELED};
use crate::transfer::protocol::{
    read_message, write_message, ControlMessage, FileChunkAckMessage, FileChunkMessage,
    FileEndMessage, FileStartMessage, FlowControlReason, FlowControlState,
    ParallelWorkerCompleteMessage, ParallelWorkerOpenMessage, TransferCompleteMessage,
    TransferFlowControlMessage, TransferManifestMessage, TransferResumeFileState,
    TransferResumePlanMessage,
};
use crate::types::{now_ms, SendDraft, TransferManifestEntry, TransferManifestEntryKind};

const STREAM_BUFFER_SIZE: usize = 1024 * 1024;
/// How many unacked 1 MiB chunks may be in flight on one TCP stream.
/// Stop-and-wait (window=1) leaves Ethernet idle waiting on RTT; a window of 16
/// keeps the pipe full for single-file transfers the same way multi-file
/// parallelism previously did by overlapping several stop-and-wait streams.
const CHUNK_ACK_WINDOW: usize = 16;
const PROGRESS_EMIT_INTERVAL_MS: u64 = 150;

struct PendingChunkAck {
    relative_path: String,
    #[allow(dead_code)]
    next_offset: u64,
    sequence: u64,
}

#[allow(dead_code)]
pub async fn send_transfer_stream_framed_v2(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    draft: &SendDraft,
    target_addr: String,
    selected_parallel_file_streams: u16,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    write_message(
        stream,
        &ControlMessage::TransferManifest(TransferManifestMessage::from_send_draft(
            transfer_id.to_string(),
            draft,
            selected_parallel_file_streams,
        )),
    )
    .await?;

    let resume_plan = match read_message(stream).await? {
        ControlMessage::TransferResumePlan(plan) if plan.transfer_id == transfer_id => plan,
        ControlMessage::TransferResumePlan(_) => {
            return Err("receiver returned a resume plan for the wrong transfer".to_string());
        }
        _ => return Err("expected receiver resume plan before streaming".to_string()),
    };
    let resume_offsets = resume_plan
        .files
        .into_iter()
        .map(|file| (file.relative_path, file.completed_bytes))
        .collect::<HashMap<_, _>>();

    let worker_count = selected_parallel_file_streams.max(1);
    if worker_count > 1 {
        send_transfer_stream_framed_v2_parallel(
            state,
            app,
            stream,
            transfer_id,
            draft,
            target_addr,
            worker_count,
            resume_offsets,
            cancel_flag,
            pause_flag,
        )
        .await?;
        return Ok(());
    }

    let started_at_ms = now_ms();
    let mut completed_bytes = resume_offsets
        .values()
        .copied()
        .sum::<u64>()
        .min(draft.total_bytes);
    let mut last_emit_ms = 0u64;
    let mut sequence = 0u64;
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
    let mut pending_acks = VecDeque::new();

    for entry in draft
        .manifest_entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::File))
    {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }
        let source_path = entry
            .source_path
            .as_ref()
            .ok_or_else(|| format!("missing source path for {}", entry.relative_path))?;
        let size = entry.size.unwrap_or(0);
        let resume_offset = resume_offsets
            .get(&entry.relative_path)
            .copied()
            .unwrap_or(0)
            .min(size);

        if resume_offset >= size {
            maybe_emit_progress(
                state,
                app,
                transfer_id,
                draft.total_bytes,
                completed_bytes,
                &format!("Skipped already received {}", entry.relative_path),
                started_at_ms,
                &mut last_emit_ms,
            )
            .await?;
            continue;
        }

        let mut file = File::open(source_path)
            .await
            .map_err(|error| format!("failed to open {}: {error}", source_path))?;
        if resume_offset > 0 {
            file.seek(SeekFrom::Start(resume_offset))
                .await
                .map_err(|error| format!("failed seeking {}: {error}", source_path))?;
        }

        write_message(
            stream,
            &ControlMessage::FileStart(FileStartMessage::new(
                transfer_id.to_string(),
                entry.relative_path.clone(),
                size,
                resume_offset,
            )),
        )
        .await?;

        let mut offset = resume_offset;
        let mut remaining = size.saturating_sub(resume_offset);
        while remaining > 0 {
            honor_sender_pause_or_cancel(
                state,
                app,
                stream,
                transfer_id,
                &mut pending_acks,
                &cancel_flag,
                &pause_flag,
            )
            .await?;

            let chunk_len = remaining.min(buffer.len() as u64) as usize;
            let read = file
                .read(&mut buffer[..chunk_len])
                .await
                .map_err(|error| format!("failed reading {}: {error}", source_path))?;
            if read == 0 {
                return Err(format!(
                    "unexpected end of file while reading {}",
                    entry.relative_path
                ));
            }

            write_message(
                stream,
                &ControlMessage::FileChunk(FileChunkMessage::new(
                    transfer_id.to_string(),
                    entry.relative_path.clone(),
                    offset,
                    read as u32,
                    sequence,
                )),
            )
            .await?;
            stream
                .write_all(&buffer[..read])
                .await
                .map_err(|error| format!("failed to stream {}: {error}", entry.relative_path))?;

            note_chunk_sent_and_manage_ack_window(
                state,
                app,
                stream,
                transfer_id,
                &mut pending_acks,
                PendingChunkAck {
                    relative_path: entry.relative_path.clone(),
                    next_offset: offset.saturating_add(read as u64),
                    sequence,
                },
                &cancel_flag,
                &pause_flag,
            )
            .await?;

            sequence = sequence.saturating_add(1);
            offset = offset.saturating_add(read as u64);
            completed_bytes = completed_bytes.saturating_add(read as u64);
            remaining = remaining.saturating_sub(read as u64);
            maybe_emit_progress(
                state,
                app,
                transfer_id,
                draft.total_bytes,
                completed_bytes,
                &entry.relative_path,
                started_at_ms,
                &mut last_emit_ms,
            )
            .await?;
        }

        flush_pending_chunk_acks(
            state,
            app,
            stream,
            transfer_id,
            &mut pending_acks,
            &cancel_flag,
            &pause_flag,
        )
        .await?;

        write_message(
            stream,
            &ControlMessage::FileEnd(FileEndMessage::new(
                transfer_id.to_string(),
                entry.relative_path.clone(),
                size,
            )),
        )
        .await?;
    }

    write_message(
        stream,
        &ControlMessage::TransferComplete(TransferCompleteMessage::new(transfer_id.to_string())),
    )
    .await?;
    stream.flush().await.map_err(|error| error.to_string())?;

    emit_progress(
        state,
        app,
        transfer_id,
        draft.total_bytes,
        draft.total_bytes,
        "Transfer completed",
        started_at_ms,
    )
    .await?;
    Ok(())
}

async fn send_transfer_stream_framed_v2_parallel(
    state: &AppState,
    app: &tauri::AppHandle,
    control_stream: &mut TcpStream,
    transfer_id: &str,
    draft: &SendDraft,
    target_addr: String,
    worker_count: u16,
    resume_offsets: HashMap<String, u64>,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    let mut pending_entries = VecDeque::new();
    for entry in draft
        .manifest_entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::File))
    {
        let size = entry.size.unwrap_or(0);
        let resume_offset = resume_offsets
            .get(&entry.relative_path)
            .copied()
            .unwrap_or(0)
            .min(size);
        if resume_offset < size {
            pending_entries.push_back(entry.clone());
        }
    }

    if pending_entries.is_empty() {
        write_message(
            control_stream,
            &ControlMessage::TransferComplete(TransferCompleteMessage::new(
                transfer_id.to_string(),
            )),
        )
        .await?;
        return Ok(());
    }

    let actual_workers = usize::from(worker_count).max(1);
    let started_at_ms = now_ms();
    let completed_bytes = Arc::new(AtomicU64::new(
        resume_offsets
            .values()
            .copied()
            .sum::<u64>()
            .min(draft.total_bytes),
    ));
    let last_emit_ms = Arc::new(AsyncMutex::new(0u64));
    let queue = Arc::new(AsyncMutex::new(pending_entries));
    let resume_offsets = Arc::new(resume_offsets);
    let active_files = Arc::new(AsyncMutex::new(HashMap::<u16, String>::new()));
    let mut handles = Vec::with_capacity(actual_workers);

    emit_progress(
        state,
        app,
        transfer_id,
        draft.total_bytes,
        completed_bytes.load(Ordering::SeqCst),
        &format!("Starting {} parallel file worker(s)", actual_workers),
        started_at_ms,
    )
    .await?;

    for worker_index in 0..actual_workers {
        let state = state.clone();
        let app = app.clone();
        let target_addr = target_addr.clone();
        let transfer_id = transfer_id.to_string();
        let queue = queue.clone();
        let resume_offsets = resume_offsets.clone();
        let completed_bytes = completed_bytes.clone();
        let last_emit_ms = last_emit_ms.clone();
        let cancel_flag = cancel_flag.clone();
        let pause_flag = pause_flag.clone();
        let active_files = active_files.clone();
        let total_bytes = draft.total_bytes;
        handles.push(tauri::async_runtime::spawn(async move {
            send_parallel_worker_framed_v2(
                &state,
                &app,
                target_addr,
                &transfer_id,
                worker_index as u16,
                queue,
                resume_offsets,
                completed_bytes,
                total_bytes,
                started_at_ms,
                last_emit_ms,
                cancel_flag,
                pause_flag,
                active_files,
            )
            .await
        }));
    }

    for handle in handles {
        match handle.await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => {
                cancel_flag.store(true, Ordering::SeqCst);
                let _ = notify_peer_cancel(control_stream, transfer_id).await;
                return Err(error);
            }
            Err(error) => {
                cancel_flag.store(true, Ordering::SeqCst);
                let _ = notify_peer_cancel(control_stream, transfer_id).await;
                return Err(format!("parallel worker task failed: {error}"));
            }
        }
    }

    write_message(
        control_stream,
        &ControlMessage::TransferComplete(TransferCompleteMessage::new(transfer_id.to_string())),
    )
    .await?;
    control_stream
        .flush()
        .await
        .map_err(|error| error.to_string())?;

    emit_progress(
        state,
        app,
        transfer_id,
        draft.total_bytes,
        draft.total_bytes,
        "Transfer completed",
        started_at_ms,
    )
    .await
}

async fn send_parallel_worker_framed_v2(
    state: &AppState,
    app: &tauri::AppHandle,
    target_addr: String,
    transfer_id: &str,
    worker_id: u16,
    queue: Arc<AsyncMutex<VecDeque<TransferManifestEntry>>>,
    resume_offsets: Arc<HashMap<String, u64>>,
    completed_bytes: Arc<AtomicU64>,
    total_bytes: u64,
    started_at_ms: u64,
    last_emit_ms: Arc<AsyncMutex<u64>>,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
    active_files: Arc<AsyncMutex<HashMap<u16, String>>>,
) -> Result<(), String> {
    let mut stream = TcpStream::connect(&target_addr).await.map_err(|error| {
        format!("worker {worker_id} could not connect to {target_addr}: {error}")
    })?;
    configure_transfer_socket(&stream);
    write_message(
        &mut stream,
        &ControlMessage::ParallelWorkerOpen(ParallelWorkerOpenMessage::new(
            transfer_id.to_string(),
            worker_id,
        )),
    )
    .await?;

    match read_message(&mut stream).await? {
        ControlMessage::ParallelWorkerReady(ready)
            if ready.transfer_id == transfer_id
                && ready.worker_id == worker_id
                && ready.accepted => {}
        ControlMessage::ParallelWorkerReady(ready)
            if ready.transfer_id == transfer_id && ready.worker_id == worker_id =>
        {
            return Err(ready
                .message
                .unwrap_or_else(|| format!("worker {worker_id} was rejected")));
        }
        _ => {
            return Err(format!(
                "worker {worker_id} received an unexpected ready frame"
            ))
        }
    }

    let mut sequence = 0u64;
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(&mut stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }

        let Some(entry) = queue.lock().await.pop_front() else {
            break;
        };

        active_files
            .lock()
            .await
            .insert(worker_id, entry.relative_path.clone());
        let send_result = send_parallel_file_framed_v2(
            state,
            app,
            &mut stream,
            transfer_id,
            worker_id,
            &entry,
            &resume_offsets,
            &completed_bytes,
            total_bytes,
            started_at_ms,
            &last_emit_ms,
            &cancel_flag,
            &pause_flag,
            &active_files,
            &mut sequence,
            &mut buffer,
        )
        .await;
        active_files.lock().await.remove(&worker_id);
        send_result?;
    }

    write_message(
        &mut stream,
        &ControlMessage::ParallelWorkerComplete(ParallelWorkerCompleteMessage::new(
            transfer_id.to_string(),
            worker_id,
        )),
    )
    .await?;
    stream.flush().await.map_err(|error| error.to_string())
}

#[allow(clippy::too_many_arguments)]
async fn send_parallel_file_framed_v2(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    worker_id: u16,
    entry: &TransferManifestEntry,
    resume_offsets: &HashMap<String, u64>,
    completed_bytes: &AtomicU64,
    total_bytes: u64,
    started_at_ms: u64,
    last_emit_ms: &AsyncMutex<u64>,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
    active_files: &AsyncMutex<HashMap<u16, String>>,
    sequence: &mut u64,
    buffer: &mut [u8],
) -> Result<(), String> {
    let source_path = entry
        .source_path
        .as_ref()
        .ok_or_else(|| format!("missing source path for {}", entry.relative_path))?;
    let size = entry.size.unwrap_or(0);
    let resume_offset = resume_offsets
        .get(&entry.relative_path)
        .copied()
        .unwrap_or(0)
        .min(size);
    if resume_offset >= size {
        return Ok(());
    }

    let mut file = File::open(source_path)
        .await
        .map_err(|error| format!("worker {worker_id} failed to open {}: {error}", source_path))?;
    if resume_offset > 0 {
        file.seek(SeekFrom::Start(resume_offset))
            .await
            .map_err(|error| {
                format!("worker {worker_id} failed seeking {}: {error}", source_path)
            })?;
    }

    write_message(
        stream,
        &ControlMessage::FileStart(FileStartMessage::new(
            transfer_id.to_string(),
            entry.relative_path.clone(),
            size,
            resume_offset,
        )),
    )
    .await?;

    let mut offset = resume_offset;
    let mut remaining = size.saturating_sub(resume_offset);
    let mut pending_acks = VecDeque::new();
    while remaining > 0 {
        honor_sender_pause_or_cancel(
            state,
            app,
            stream,
            transfer_id,
            &mut pending_acks,
            cancel_flag,
            pause_flag,
        )
        .await?;

        let chunk_len = remaining.min(buffer.len() as u64) as usize;
        let read = file.read(&mut buffer[..chunk_len]).await.map_err(|error| {
            format!("worker {worker_id} failed reading {}: {error}", source_path)
        })?;
        if read == 0 {
            return Err(format!(
                "unexpected end of file while worker {worker_id} read {}",
                entry.relative_path
            ));
        }

        write_message(
            stream,
            &ControlMessage::FileChunk(FileChunkMessage::new(
                transfer_id.to_string(),
                entry.relative_path.clone(),
                offset,
                read as u32,
                *sequence,
            )),
        )
        .await?;
        stream.write_all(&buffer[..read]).await.map_err(|error| {
            format!(
                "worker {worker_id} failed to stream {}: {error}",
                entry.relative_path
            )
        })?;

        note_chunk_sent_and_manage_ack_window(
            state,
            app,
            stream,
            transfer_id,
            &mut pending_acks,
            PendingChunkAck {
                relative_path: entry.relative_path.clone(),
                next_offset: offset.saturating_add(read as u64),
                sequence: *sequence,
            },
            cancel_flag,
            pause_flag,
        )
        .await?;

        *sequence = sequence.saturating_add(1);
        offset = offset.saturating_add(read as u64);
        let next_completed = completed_bytes
            .fetch_add(read as u64, Ordering::SeqCst)
            .saturating_add(read as u64);
        remaining = remaining.saturating_sub(read as u64);

        let current_files = joined_active_files(active_files, &entry.relative_path).await;
        maybe_emit_parallel_progress(
            state,
            app,
            transfer_id,
            total_bytes,
            next_completed,
            &current_files,
            started_at_ms,
            last_emit_ms,
        )
        .await?;
    }

    flush_pending_chunk_acks(
        state,
        app,
        stream,
        transfer_id,
        &mut pending_acks,
        cancel_flag,
        pause_flag,
    )
    .await?;

    write_message(
        stream,
        &ControlMessage::FileEnd(FileEndMessage::new(
            transfer_id.to_string(),
            entry.relative_path.clone(),
            size,
        )),
    )
    .await
}

pub async fn send_transfer_stream(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    draft: &SendDraft,
    selected_parallel_file_streams: u16,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    write_message(
        stream,
        &ControlMessage::TransferManifest(TransferManifestMessage::from_send_draft(
            transfer_id.to_string(),
            draft,
            selected_parallel_file_streams,
        )),
    )
    .await?;

    let resume_plan = match read_message(stream).await? {
        ControlMessage::TransferResumePlan(plan) if plan.transfer_id == transfer_id => plan,
        ControlMessage::TransferResumePlan(_) => {
            return Err("receiver returned a resume plan for the wrong transfer".to_string());
        }
        _ => return Err("expected receiver resume plan before streaming".to_string()),
    };
    let resume_offsets = resume_plan
        .files
        .into_iter()
        .map(|file| (file.relative_path, file.completed_bytes))
        .collect::<HashMap<_, _>>();

    let started_at_ms = now_ms();
    let mut completed_bytes = resume_offsets
        .values()
        .copied()
        .sum::<u64>()
        .min(draft.total_bytes);
    let mut last_emit_ms = 0u64;
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];

    for entry in draft
        .manifest_entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::File))
    {
        if cancel_flag.load(Ordering::SeqCst) {
            return Err("Transfer canceled by user.".to_string());
        }
        let source_path = entry
            .source_path
            .as_ref()
            .ok_or_else(|| format!("missing source path for {}", entry.relative_path))?;
        let size = entry.size.unwrap_or(0);
        let resume_offset = resume_offsets
            .get(&entry.relative_path)
            .copied()
            .unwrap_or(0)
            .min(size);

        if resume_offset >= size {
            maybe_emit_progress(
                state,
                app,
                transfer_id,
                draft.total_bytes,
                completed_bytes,
                &format!("Skipped already received {}", entry.relative_path),
                started_at_ms,
                &mut last_emit_ms,
            )
            .await?;
            continue;
        }

        let mut file = File::open(source_path)
            .await
            .map_err(|error| format!("failed to open {}: {error}", source_path))?;
        if resume_offset > 0 {
            file.seek(SeekFrom::Start(resume_offset))
                .await
                .map_err(|error| format!("failed seeking {}: {error}", source_path))?;
        }

        write_message(
            stream,
            &ControlMessage::FileStart(FileStartMessage::new(
                transfer_id.to_string(),
                entry.relative_path.clone(),
                size,
                resume_offset,
            )),
        )
        .await?;

        let mut remaining = size.saturating_sub(resume_offset);
        while remaining > 0 {
            wait_while_paused(&cancel_flag, &pause_flag).await?;
            if cancel_flag.load(Ordering::SeqCst) {
                return Err("Transfer canceled by user.".to_string());
            }
            let chunk_len = remaining.min(buffer.len() as u64) as usize;
            let read = file
                .read(&mut buffer[..chunk_len])
                .await
                .map_err(|error| format!("failed reading {}: {error}", source_path))?;
            if read == 0 {
                return Err(format!(
                    "unexpected end of file while reading {}",
                    entry.relative_path
                ));
            }

            stream
                .write_all(&buffer[..read])
                .await
                .map_err(|error| format!("failed to stream {}: {error}", entry.relative_path))?;

            completed_bytes += read as u64;
            remaining = remaining.saturating_sub(read as u64);
            maybe_emit_progress(
                state,
                app,
                transfer_id,
                draft.total_bytes,
                completed_bytes,
                &entry.relative_path,
                started_at_ms,
                &mut last_emit_ms,
            )
            .await?;
        }
    }

    write_message(
        stream,
        &ControlMessage::TransferComplete(TransferCompleteMessage::new(transfer_id.to_string())),
    )
    .await?;
    stream.flush().await.map_err(|error| error.to_string())?;

    emit_progress(
        state,
        app,
        transfer_id,
        draft.total_bytes,
        draft.total_bytes,
        "Transfer completed",
        started_at_ms,
    )
    .await?;
    Ok(())
}

pub async fn receive_transfer_stream(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    peer_name: &str,
    save_path: &str,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<String, String> {
    let manifest = read_incoming_manifest(stream, transfer_id).await?;

    let destination_root = build_destination_root(Path::new(save_path), peer_name, transfer_id);
    fs::create_dir_all(&destination_root)
        .await
        .map_err(|error| format!("failed to create {}: {error}", destination_root.display()))?;

    let receive_result = receive_transfer_stream_inner(
        state,
        app,
        stream,
        transfer_id,
        &manifest,
        &destination_root,
        cancel_flag,
        pause_flag,
    )
    .await;

    match receive_result {
        Ok(()) => Ok(destination_root.display().to_string()),
        Err(error) => Err(error),
    }
}

async fn receive_transfer_stream_inner(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    manifest: &TransferManifestMessage,
    destination_root: &Path,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    for entry in manifest
        .entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::Directory))
    {
        let directory_path =
            resolve_destination_path(&destination_root, &entry.relative_path, false)?;
        fs::create_dir_all(&directory_path)
            .await
            .map_err(|error| format!("failed to create {}: {error}", directory_path.display()))?;
    }

    let resume_files = build_resume_plan(manifest, destination_root).await?;
    let initial_completed_bytes = resume_files
        .iter()
        .map(|file| file.completed_bytes)
        .sum::<u64>()
        .min(manifest.total_bytes);
    write_message(
        stream,
        &ControlMessage::TransferResumePlan(TransferResumePlanMessage::new(
            transfer_id.to_string(),
            resume_files,
        )),
    )
    .await?;

    let started_at_ms = now_ms();
    let mut completed_bytes = initial_completed_bytes;
    let mut last_emit_ms = 0u64;
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        completed_bytes,
        &format!("Saving into {}", destination_root.display()),
        started_at_ms,
    )
    .await?;

    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }
        match read_message(stream).await? {
            ControlMessage::FileStart(file_start) => {
                if file_start.transfer_id != transfer_id {
                    return Err("received file bytes for the wrong transfer".to_string());
                }
                if file_start.offset > file_start.size {
                    return Err(format!(
                        "invalid resume offset for {}",
                        file_start.relative_path
                    ));
                }

                let output_path =
                    resolve_destination_path(destination_root, &file_start.relative_path, true)?;
                if let Some(parent) = output_path.parent() {
                    fs::create_dir_all(parent).await.map_err(|error| {
                        format!("failed to create {}: {error}", parent.display())
                    })?;
                }

                let temporary_output_path = temporary_output_path(&output_path);
                if file_start.offset == 0
                    && fs::try_exists(&temporary_output_path)
                        .await
                        .unwrap_or(false)
                {
                    let _ = fs::remove_file(&temporary_output_path).await;
                }

                let mut file = if file_start.offset > 0 {
                    let existing_size = fs::metadata(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to inspect partial {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                        .len();
                    if existing_size != file_start.offset {
                        return Err(format!(
                            "partial file size changed for {}",
                            file_start.relative_path
                        ));
                    }
                    File::options()
                        .append(true)
                        .open(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to reopen {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                } else {
                    File::create(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to create {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                };

                let mut remaining = file_start.size.saturating_sub(file_start.offset);
                let mut written = file_start.offset;
                while remaining > 0 {
                    wait_while_paused(&cancel_flag, &pause_flag).await?;
                    if cancel_flag.load(Ordering::SeqCst) {
                        return Err("Transfer canceled by user.".to_string());
                    }
                    let chunk_len = remaining.min(buffer.len() as u64) as usize;
                    stream
                        .read_exact(&mut buffer[..chunk_len])
                        .await
                        .map_err(|error| {
                            format!("failed receiving {}: {error}", file_start.relative_path)
                        })?;
                    file.write_all(&buffer[..chunk_len])
                        .await
                        .map_err(|error| {
                            format!(
                                "failed writing {}: {error}",
                                temporary_output_path.display()
                            )
                        })?;

                    written += chunk_len as u64;
                    completed_bytes += chunk_len as u64;
                    remaining = remaining.saturating_sub(chunk_len as u64);
                    maybe_emit_progress(
                        state,
                        app,
                        transfer_id,
                        manifest.total_bytes,
                        completed_bytes,
                        &file_start.relative_path,
                        started_at_ms,
                        &mut last_emit_ms,
                    )
                    .await?;
                }

                if written != file_start.size {
                    return Err(format!(
                        "received {} bytes for {} but expected {}",
                        written, file_start.relative_path, file_start.size
                    ));
                }

                file.flush().await.map_err(|error| {
                    format!(
                        "failed to flush {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                file.sync_all().await.map_err(|error| {
                    format!(
                        "failed to sync {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                drop(file);

                if fs::try_exists(&output_path).await.unwrap_or(false) {
                    fs::remove_file(&output_path).await.map_err(|error| {
                        format!("failed to replace {}: {error}", output_path.display())
                    })?;
                }
                fs::rename(&temporary_output_path, &output_path)
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to finalize {} from {}: {error}",
                            output_path.display(),
                            temporary_output_path.display()
                        )
                    })?;
            }
            ControlMessage::TransferComplete(done) => {
                if done.transfer_id != transfer_id {
                    return Err("received completion for the wrong transfer".to_string());
                }
                break;
            }
            _ => return Err("unexpected control frame during file streaming".to_string()),
        }
    }

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        manifest.total_bytes,
        &format!("Saved into {}", destination_root.display()),
        started_at_ms,
    )
    .await?;

    Ok(())
}

#[allow(dead_code)]
pub async fn receive_transfer_stream_framed_v2(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    peer_name: &str,
    save_path: &str,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<String, String> {
    let manifest = read_incoming_manifest(stream, transfer_id).await?;

    let destination_root = build_destination_root(Path::new(save_path), peer_name, transfer_id);
    fs::create_dir_all(&destination_root)
        .await
        .map_err(|error| format!("failed to create {}: {error}", destination_root.display()))?;

    if manifest.selected_parallel_file_streams > 1 {
        receive_transfer_stream_framed_v2_parallel(
            state,
            app,
            stream,
            transfer_id,
            &manifest,
            &destination_root,
            cancel_flag,
            pause_flag,
        )
        .await?;
        return Ok(destination_root.display().to_string());
    }

    receive_transfer_stream_framed_v2_inner(
        state,
        app,
        stream,
        transfer_id,
        &manifest,
        &destination_root,
        cancel_flag,
        pause_flag,
    )
    .await?;
    Ok(destination_root.display().to_string())
}

#[allow(dead_code)]
pub async fn receive_parallel_worker_framed_v2(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    session: Arc<ParallelIncomingTransferSession>,
    worker_id: u16,
) -> Result<(), String> {
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
    let mut active_file: Option<(String, PathBuf, File, u64, u64)> = None;
    let mut last_emit_ms = 0u64;

    loop {
        if session.cancel_flag.load(Ordering::SeqCst) {
            return Err("Transfer canceled by user.".to_string());
        }

        let message = next_control_message_honoring_pause(
            state,
            app,
            stream,
            &session.transfer_id,
            &session.cancel_flag,
            &session.pause_flag,
        )
        .await?;

        match message {
            ControlMessage::FileStart(file_start) => {
                if file_start.transfer_id != session.transfer_id {
                    return Err("worker received file bytes for the wrong transfer".to_string());
                }
                if active_file.is_some() {
                    return Err(
                        "worker received a second file before finishing the first".to_string()
                    );
                }
                if file_start.offset > file_start.size {
                    return Err(format!(
                        "invalid resume offset for {}",
                        file_start.relative_path
                    ));
                }

                let output_path = resolve_destination_path(
                    &session.destination_root,
                    &file_start.relative_path,
                    true,
                )?;
                if let Some(parent) = output_path.parent() {
                    fs::create_dir_all(parent).await.map_err(|error| {
                        format!("failed to create {}: {error}", parent.display())
                    })?;
                }

                let temporary_output_path = temporary_output_path(&output_path);
                if file_start.offset == 0
                    && fs::try_exists(&temporary_output_path)
                        .await
                        .unwrap_or(false)
                {
                    let _ = fs::remove_file(&temporary_output_path).await;
                }

                let file = if file_start.offset > 0 {
                    let existing_size = fs::metadata(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to inspect partial {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                        .len();
                    if existing_size != file_start.offset {
                        return Err(format!(
                            "partial file size changed for {}",
                            file_start.relative_path
                        ));
                    }
                    File::options()
                        .append(true)
                        .open(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to reopen {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                } else {
                    File::create(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to create {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                };

                active_file = Some((
                    file_start.relative_path,
                    temporary_output_path,
                    file,
                    file_start.offset,
                    file_start.size,
                ));
                if let Some((relative_path, ..)) = active_file.as_ref() {
                    session
                        .active_files
                        .lock()
                        .await
                        .insert(worker_id, relative_path.clone());
                }
            }
            ControlMessage::FileChunk(chunk) => {
                let Some((relative_path, _temporary_output_path, file, written, size)) =
                    active_file.as_mut()
                else {
                    return Err("worker received file chunk before file start".to_string());
                };
                if chunk.transfer_id != session.transfer_id || chunk.relative_path != *relative_path
                {
                    return Err("worker received chunk for the wrong transfer or file".to_string());
                }
                if chunk.offset != *written {
                    return Err(format!(
                        "unexpected chunk offset for {}: got {}, expected {}",
                        relative_path, chunk.offset, written
                    ));
                }
                if chunk.length as u64 > size.saturating_sub(*written) {
                    return Err(format!(
                        "chunk exceeds remaining file size for {}",
                        relative_path
                    ));
                }

                let mut remaining = chunk.length as usize;
                while remaining > 0 {
                    let read_len = remaining.min(buffer.len());
                    stream
                        .read_exact(&mut buffer[..read_len])
                        .await
                        .map_err(|error| format!("failed receiving {}: {error}", relative_path))?;
                    file.write_all(&buffer[..read_len])
                        .await
                        .map_err(|error| format!("failed writing {}: {error}", relative_path))?;
                    remaining -= read_len;
                    *written = written.saturating_add(read_len as u64);
                    let completed = session
                        .completed_bytes
                        .fetch_add(read_len as u64, Ordering::SeqCst)
                        .saturating_add(read_len as u64);
                    let current_files =
                        joined_active_files(&session.active_files, relative_path).await;
                    maybe_emit_progress(
                        state,
                        app,
                        &session.transfer_id,
                        session.total_bytes,
                        completed,
                        &current_files,
                        session.started_at_ms,
                        &mut last_emit_ms,
                    )
                    .await?;
                }

                write_message(
                    stream,
                    &ControlMessage::FileChunkAck(FileChunkAckMessage::new(
                        session.transfer_id.clone(),
                        relative_path.clone(),
                        *written,
                        chunk.sequence,
                    )),
                )
                .await?;
            }
            ControlMessage::FileEnd(file_end) => {
                let Some((relative_path, temporary_output_path, mut file, written, size)) =
                    active_file.take()
                else {
                    return Err("worker received file end before file start".to_string());
                };
                if file_end.transfer_id != session.transfer_id
                    || file_end.relative_path != relative_path
                {
                    return Err(
                        "worker received file end for the wrong transfer or file".to_string()
                    );
                }
                if written != size || file_end.size != size {
                    return Err(format!("worker received incomplete file {}", relative_path));
                }

                file.flush().await.map_err(|error| {
                    format!(
                        "failed to flush {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                file.sync_all().await.map_err(|error| {
                    format!(
                        "failed to sync {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                drop(file);

                let output_path =
                    resolve_destination_path(&session.destination_root, &relative_path, true)?;
                if fs::try_exists(&output_path).await.unwrap_or(false) {
                    fs::remove_file(&output_path).await.map_err(|error| {
                        format!("failed to replace {}: {error}", output_path.display())
                    })?;
                }
                fs::rename(&temporary_output_path, &output_path)
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to finalize {} from {}: {error}",
                            output_path.display(),
                            temporary_output_path.display()
                        )
                    })?;
                session.active_files.lock().await.remove(&worker_id);
            }
            ControlMessage::ParallelWorkerComplete(done) => {
                if done.transfer_id != session.transfer_id || done.worker_id != worker_id {
                    return Err("worker completion frame did not match this worker".to_string());
                }
                if active_file.is_some() {
                    return Err("worker completed while a file was still open".to_string());
                }
                return Ok(());
            }
            ControlMessage::TransferFlowControl(flow)
                if flow.transfer_id == session.transfer_id =>
            {
                handle_peer_flow_control_for_receiver(
                    state,
                    app,
                    stream,
                    &session.transfer_id,
                    flow,
                    &session.cancel_flag,
                    &session.pause_flag,
                )
                .await?;
            }
            ControlMessage::TransferFlowControl(_) => {
                return Err("worker received flow control for the wrong transfer".to_string());
            }
            _ => return Err("unexpected worker control frame during parallel receive".to_string()),
        }
    }
}

async fn receive_transfer_stream_framed_v2_parallel(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    manifest: &TransferManifestMessage,
    destination_root: &Path,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    for entry in manifest
        .entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::Directory))
    {
        let directory_path =
            resolve_destination_path(destination_root, &entry.relative_path, false)?;
        fs::create_dir_all(&directory_path)
            .await
            .map_err(|error| format!("failed to create {}: {error}", directory_path.display()))?;
    }

    let resume_files = build_resume_plan(manifest, destination_root).await?;
    let initial_completed_bytes = resume_files
        .iter()
        .map(|file| file.completed_bytes)
        .sum::<u64>()
        .min(manifest.total_bytes);
    let worker_count = if initial_completed_bytes >= manifest.total_bytes {
        0
    } else {
        usize::from(manifest.selected_parallel_file_streams.max(1))
    };
    let session = Arc::new(ParallelIncomingTransferSession::new(
        transfer_id.to_string(),
        destination_root.to_path_buf(),
        manifest.total_bytes,
        initial_completed_bytes,
        worker_count,
        cancel_flag,
        pause_flag,
    ));
    if worker_count > 0 {
        state
            .register_parallel_incoming_session(transfer_id.to_string(), session.clone())
            .await;
    }

    write_message(
        stream,
        &ControlMessage::TransferResumePlan(TransferResumePlanMessage::new(
            transfer_id.to_string(),
            resume_files,
        )),
    )
    .await?;

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        initial_completed_bytes,
        &format!(
            "Receiving with {} parallel file worker(s)",
            worker_count.max(1)
        ),
        session.started_at_ms,
    )
    .await?;

    let mut transfer_complete = false;
    while let Some(message) =
        next_parallel_completion_message(stream, &session, transfer_complete).await?
    {
        match message {
            ControlMessage::TransferComplete(done) if done.transfer_id == transfer_id => {
                transfer_complete = true;
            }
            ControlMessage::TransferFlowControl(flow) if flow.transfer_id == transfer_id => {
                if matches!(flow.state, FlowControlState::CancelRequested) {
                    session.set_error(PEER_CANCELED.to_string()).await;
                    return Err(PEER_CANCELED.to_string());
                }
                handle_peer_flow_control_for_receiver(
                    state,
                    app,
                    stream,
                    transfer_id,
                    flow,
                    &session.cancel_flag,
                    &session.pause_flag,
                )
                .await?;
            }
            ControlMessage::TransferComplete(_) => {
                return Err("received completion for the wrong transfer".to_string());
            }
            _ => return Err("expected transfer completion on parallel control channel".to_string()),
        }
    }

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        manifest.total_bytes,
        &format!("Saved into {}", destination_root.display()),
        session.started_at_ms,
    )
    .await
}

async fn read_incoming_manifest(
    stream: &mut TcpStream,
    transfer_id: &str,
) -> Result<TransferManifestMessage, String> {
    match read_message(stream).await? {
        ControlMessage::TransferManifest(manifest) if manifest.transfer_id == transfer_id => {
            Ok(manifest)
        }
        ControlMessage::TransferFlowControl(flow)
            if flow.transfer_id == transfer_id
                && matches!(flow.state, FlowControlState::CancelRequested) =>
        {
            Err(PEER_CANCELED.to_string())
        }
        ControlMessage::TransferManifest(_) => {
            Err("received a manifest for the wrong transfer".to_string())
        }
        _ => Err("expected a transfer manifest after approval".to_string()),
    }
}

/// The completion frame and workers use separate TCP connections. Keep reading
/// the control channel while workers flush files, so their EOF cannot hide a
/// cancellation, including when the displayed byte count has already hit 100%.
async fn next_parallel_completion_message(
    stream: &mut TcpStream,
    session: &ParallelIncomingTransferSession,
    transfer_complete: bool,
) -> Result<Option<ControlMessage>, String> {
    let mut byte = [0u8; 1];
    tokio::select! {
        biased;
        _ = async {
            while !session.cancel_flag.load(Ordering::SeqCst) {
                sleep(Duration::from_millis(50)).await;
            }
        } => {
            let _ = notify_peer_cancel(stream, &session.transfer_id).await;
            Err(USER_CANCELED.to_string())
        }
        readable = stream.peek(&mut byte) => {
            // Only peek participates in select; never abandon a partial frame
            // when the last worker finishes at the same instant.
            let message = match readable {
                Ok(_) => tokio::time::timeout(Duration::from_secs(5), read_message(stream)).await
                    .map_err(|_| "Peer did not finish the control message.".to_string())?,
                Err(error) => Err(error.to_string()),
            };
            match message {
                Ok(message) => Ok(Some(message)),
                Err(error) => Err(session.error().await.filter(|error| error == PEER_CANCELED).unwrap_or(error)),
            }
        }
        finished = session.wait_finished(), if transfer_complete => {
            match finished {
                // Socket readiness may be delivered one scheduler turn after
                // the worker notification, even when Cancel is already in flight.
                Ok(()) => peek_then_read_message(stream, 25).await,
                Err(error) if error == PEER_CANCELED => Err(error),
                Err(error) => Err(prefer_peer_cancel(stream, &session.transfer_id, error).await),
            }
        }
    }
}

pub(super) async fn prefer_peer_cancel(
    stream: &mut TcpStream,
    transfer_id: &str,
    error: String,
) -> String {
    // A worker's EOF may arrive slightly ahead of Cancel on the control socket.
    // Give that explicit message a bounded chance to arrive; a real disconnect
    // without Cancel remains a failure.
    let next = tokio::time::timeout(Duration::from_millis(500), async {
        let mut byte = [0u8; 1];
        stream
            .peek(&mut byte)
            .await
            .map_err(|error| error.to_string())?;
        read_message(stream).await
    })
    .await;
    match next {
        Ok(Ok(ControlMessage::TransferFlowControl(flow)))
            if flow.transfer_id == transfer_id
                && matches!(flow.state, FlowControlState::CancelRequested) =>
        {
            PEER_CANCELED.to_string()
        }
        _ => error,
    }
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    use tokio::net::TcpListener;
    use tokio::time::timeout;

    async fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let sender = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (receiver, _) = listener.accept().await.unwrap();
        (sender, receiver)
    }

    fn session(workers: usize) -> Arc<ParallelIncomingTransferSession> {
        Arc::new(ParallelIncomingTransferSession::new(
            "test-cancel".into(),
            PathBuf::from("unused"),
            1600,
            1600,
            workers,
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicBool::new(false)),
        ))
    }

    fn cancel_message(transfer_id: &str) -> ControlMessage {
        ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
            transfer_id.into(),
            FlowControlState::CancelRequested,
            FlowControlReason::User,
        ))
    }

    #[tokio::test]
    async fn worker_eof_waits_for_sender_cancel_on_the_control_socket() {
        let (mut sender, mut receiver) = connected_pair().await;
        let session = session(1);
        session.set_error("early eof".into()).await;
        session.mark_worker_finished();
        let task = tokio::spawn(async move {
            next_parallel_completion_message(&mut receiver, &session, true).await
        });
        // Simulate independent worker and control connections arriving out of order.
        sleep(Duration::from_millis(30)).await;
        write_message(&mut sender, &cancel_message("test-cancel"))
            .await
            .unwrap();
        assert_eq!(
            timeout(Duration::from_secs(1), task)
                .await
                .unwrap()
                .unwrap()
                .unwrap_err(),
            PEER_CANCELED
        );
    }

    #[tokio::test]
    async fn queued_cancel_wins_even_when_all_workers_have_finished() {
        let (mut sender, mut receiver) = connected_pair().await;
        write_message(&mut sender, &cancel_message("test-cancel"))
            .await
            .unwrap();
        let result = next_parallel_completion_message(&mut receiver, &session(0), true)
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(result, ControlMessage::TransferFlowControl(flow)
            if matches!(flow.state, FlowControlState::CancelRequested)));
    }

    #[tokio::test]
    async fn worker_completion_does_not_interrupt_a_fragmented_control_frame() {
        let (mut sender, mut receiver) = connected_pair().await;
        let session = session(1);
        let task_session = session.clone();
        let payload = serde_json::to_vec(&cancel_message("test-cancel")).unwrap();
        let mut frame = (payload.len() as u32).to_be_bytes().to_vec();
        frame.extend(payload);
        sender.write_all(&frame[..2]).await.unwrap();
        let task = tokio::spawn(async move {
            next_parallel_completion_message(&mut receiver, &task_session, true).await
        });
        tokio::task::yield_now().await;
        session.mark_worker_finished();
        sender.write_all(&frame[2..]).await.unwrap();
        let result = timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(matches!(result, ControlMessage::TransferFlowControl(flow)
            if matches!(flow.state, FlowControlState::CancelRequested)));
    }

    #[tokio::test]
    async fn worker_disconnect_without_cancel_stays_a_failure() {
        let (sender, mut receiver) = connected_pair().await;
        let session = session(1);
        session.set_error("early eof".into()).await;
        session.mark_worker_finished();
        let task = tokio::spawn(async move {
            next_parallel_completion_message(&mut receiver, &session, true).await
        });
        sleep(Duration::from_millis(30)).await;
        drop(sender);
        let error = timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap_err();
        assert_eq!(
            crate::transfer::error::transfer_error_message(error, false),
            crate::transfer::error::PEER_DISCONNECTED
        );
    }

    #[tokio::test]
    async fn explicit_worker_cancel_is_not_replaced_by_an_earlier_eof() {
        let (sender, mut receiver) = connected_pair().await;
        let session = session(1);
        session.set_error("early eof".into()).await;
        session.set_error(PEER_CANCELED.into()).await;
        drop(sender);
        let error = next_parallel_completion_message(&mut receiver, &session, false)
            .await
            .unwrap_err();
        assert_eq!(error, PEER_CANCELED);
    }

    #[tokio::test]
    async fn cancel_after_acceptance_before_manifest_has_the_correct_reason() {
        let (mut sender, mut receiver) = connected_pair().await;
        write_message(&mut sender, &cancel_message("test-cancel"))
            .await
            .unwrap();
        assert_eq!(
            read_incoming_manifest(&mut receiver, "test-cancel")
                .await
                .unwrap_err(),
            PEER_CANCELED
        );
    }

    #[tokio::test]
    async fn cancel_for_another_transfer_does_not_mask_a_worker_error() {
        let (mut sender, mut receiver) = connected_pair().await;
        write_message(&mut sender, &cancel_message("other-transfer"))
            .await
            .unwrap();
        assert_eq!(
            prefer_peer_cancel(&mut receiver, "test-cancel", "disk full".into()).await,
            "disk full"
        );
    }
}

#[allow(dead_code)]
async fn receive_transfer_stream_framed_v2_inner(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    manifest: &TransferManifestMessage,
    destination_root: &Path,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    for entry in manifest
        .entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::Directory))
    {
        let directory_path =
            resolve_destination_path(&destination_root, &entry.relative_path, false)?;
        fs::create_dir_all(&directory_path)
            .await
            .map_err(|error| format!("failed to create {}: {error}", directory_path.display()))?;
    }

    let resume_files = build_resume_plan(manifest, destination_root).await?;
    let initial_completed_bytes = resume_files
        .iter()
        .map(|file| file.completed_bytes)
        .sum::<u64>()
        .min(manifest.total_bytes);
    write_message(
        stream,
        &ControlMessage::TransferResumePlan(TransferResumePlanMessage::new(
            transfer_id.to_string(),
            resume_files,
        )),
    )
    .await?;

    let started_at_ms = now_ms();
    let mut completed_bytes = initial_completed_bytes;
    let mut last_emit_ms = 0u64;
    let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
    let mut active_file: Option<(String, PathBuf, File, u64, u64)> = None;

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        completed_bytes,
        &format!("Saving into {}", destination_root.display()),
        started_at_ms,
    )
    .await?;

    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            return Err("Transfer canceled by user.".to_string());
        }

        let message = next_control_message_honoring_pause(
            state,
            app,
            stream,
            transfer_id,
            &cancel_flag,
            &pause_flag,
        )
        .await?;

        match message {
            ControlMessage::FileStart(file_start) => {
                if file_start.transfer_id != transfer_id {
                    return Err("received file bytes for the wrong transfer".to_string());
                }
                if file_start.offset > file_start.size {
                    return Err(format!(
                        "invalid resume offset for {}",
                        file_start.relative_path
                    ));
                }

                let output_path =
                    resolve_destination_path(destination_root, &file_start.relative_path, true)?;
                if let Some(parent) = output_path.parent() {
                    fs::create_dir_all(parent).await.map_err(|error| {
                        format!("failed to create {}: {error}", parent.display())
                    })?;
                }

                let temporary_output_path = temporary_output_path(&output_path);
                if file_start.offset == 0
                    && fs::try_exists(&temporary_output_path)
                        .await
                        .unwrap_or(false)
                {
                    let _ = fs::remove_file(&temporary_output_path).await;
                }

                let file = if file_start.offset > 0 {
                    let existing_size = fs::metadata(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to inspect partial {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                        .len();
                    if existing_size != file_start.offset {
                        return Err(format!(
                            "partial file size changed for {}",
                            file_start.relative_path
                        ));
                    }
                    File::options()
                        .append(true)
                        .open(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to reopen {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                } else {
                    File::create(&temporary_output_path)
                        .await
                        .map_err(|error| {
                            format!(
                                "failed to create {}: {error}",
                                temporary_output_path.display()
                            )
                        })?
                };

                active_file = Some((
                    file_start.relative_path,
                    temporary_output_path,
                    file,
                    file_start.offset,
                    file_start.size,
                ));
            }
            ControlMessage::FileChunk(chunk) => {
                let Some((relative_path, _temporary_output_path, file, written, size)) =
                    active_file.as_mut()
                else {
                    return Err("received file chunk before file start".to_string());
                };
                if chunk.transfer_id != transfer_id || chunk.relative_path != *relative_path {
                    return Err("received chunk for the wrong transfer or file".to_string());
                }
                if chunk.offset != *written {
                    return Err(format!(
                        "unexpected chunk offset for {}: got {}, expected {}",
                        relative_path, chunk.offset, written
                    ));
                }
                if chunk.length as u64 > size.saturating_sub(*written) {
                    return Err(format!(
                        "chunk exceeds remaining file size for {}",
                        relative_path
                    ));
                }

                let mut remaining = chunk.length as usize;
                while remaining > 0 {
                    let read_len = remaining.min(buffer.len());
                    stream
                        .read_exact(&mut buffer[..read_len])
                        .await
                        .map_err(|error| format!("failed receiving {}: {error}", relative_path))?;
                    file.write_all(&buffer[..read_len])
                        .await
                        .map_err(|error| format!("failed writing {}: {error}", relative_path))?;
                    remaining -= read_len;
                    *written = written.saturating_add(read_len as u64);
                    completed_bytes = completed_bytes.saturating_add(read_len as u64);
                }

                maybe_emit_progress(
                    state,
                    app,
                    transfer_id,
                    manifest.total_bytes,
                    completed_bytes,
                    relative_path,
                    started_at_ms,
                    &mut last_emit_ms,
                )
                .await?;

                write_message(
                    stream,
                    &ControlMessage::FileChunkAck(FileChunkAckMessage::new(
                        transfer_id.to_string(),
                        relative_path.clone(),
                        *written,
                        chunk.sequence,
                    )),
                )
                .await?;
            }
            ControlMessage::FileEnd(file_end) => {
                let Some((relative_path, temporary_output_path, mut file, written, size)) =
                    active_file.take()
                else {
                    return Err("received file end before file start".to_string());
                };
                if file_end.transfer_id != transfer_id || file_end.relative_path != relative_path {
                    return Err("received file end for the wrong transfer or file".to_string());
                }
                if written != size || file_end.size != size {
                    return Err(format!("received incomplete file {}", relative_path));
                }

                file.flush().await.map_err(|error| {
                    format!(
                        "failed to flush {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                file.sync_all().await.map_err(|error| {
                    format!(
                        "failed to sync {}: {error}",
                        temporary_output_path.display()
                    )
                })?;
                drop(file);

                let output_path = resolve_destination_path(destination_root, &relative_path, true)?;
                if fs::try_exists(&output_path).await.unwrap_or(false) {
                    fs::remove_file(&output_path).await.map_err(|error| {
                        format!("failed to replace {}: {error}", output_path.display())
                    })?;
                }
                fs::rename(&temporary_output_path, &output_path)
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to finalize {} from {}: {error}",
                            output_path.display(),
                            temporary_output_path.display()
                        )
                    })?;
            }
            ControlMessage::TransferComplete(done) => {
                if done.transfer_id != transfer_id {
                    return Err("received completion for the wrong transfer".to_string());
                }
                if active_file.is_some() {
                    return Err("transfer completed while a file was still open".to_string());
                }
                break;
            }
            ControlMessage::TransferFlowControl(flow) if flow.transfer_id == transfer_id => {
                handle_peer_flow_control_for_receiver(
                    state,
                    app,
                    stream,
                    transfer_id,
                    flow,
                    &cancel_flag,
                    &pause_flag,
                )
                .await?;
            }
            ControlMessage::TransferFlowControl(_) => {
                return Err("received flow control for the wrong transfer".to_string());
            }
            _ => return Err("unexpected control frame during framed file streaming".to_string()),
        }
    }

    emit_progress(
        state,
        app,
        transfer_id,
        manifest.total_bytes,
        manifest.total_bytes,
        &format!("Saved into {}", destination_root.display()),
        started_at_ms,
    )
    .await?;

    Ok(())
}

/// Wait up to `timeout_ms` for the peer to send any byte on the stream, then
/// read one full control message. Uses `TcpStream::peek` for the wait phase,
/// which is cancel-safe (it does not consume bytes from the socket buffer),
/// so a timeout firing while peeking will NOT desync the framing protocol.
/// Returns `Ok(Some(msg))` if a message was fully read, `Ok(None)` on timeout,
/// or `Err(_)` on I/O / decode errors / closed connection.
#[allow(dead_code)]
async fn peek_then_read_message(
    stream: &mut TcpStream,
    timeout_ms: u64,
) -> Result<Option<ControlMessage>, String> {
    let mut peek_buf = [0u8; 1];
    match tokio::time::timeout(
        Duration::from_millis(timeout_ms),
        stream.peek(&mut peek_buf),
    )
    .await
    {
        Ok(Ok(0)) => Err("peer closed the connection".to_string()),
        Ok(Ok(_)) => read_message(stream).await.map(Some),
        Ok(Err(error)) => Err(error.to_string()),
        Err(_) => Ok(None),
    }
}

#[allow(dead_code)]
async fn notify_peer_cancel(stream: &mut TcpStream, transfer_id: &str) -> Result<(), String> {
    write_message(
        stream,
        &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
            transfer_id.to_string(),
            FlowControlState::CancelRequested,
            FlowControlReason::User,
        )),
    )
    .await
}

#[allow(dead_code)]
async fn handle_peer_flow_control_for_receiver(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    flow: TransferFlowControlMessage,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    match flow.state {
        FlowControlState::Paused => {
            pause_flag.store(true, Ordering::SeqCst);
            state
                .set_transfer_status(
                    transfer_id,
                    crate::types::TransferStatus::Paused,
                    Some("Peer paused the transfer.".to_string()),
                )
                .await;
            emit_transfers(state, app).await?;
            loop {
                if cancel_flag.load(Ordering::SeqCst) {
                    notify_peer_cancel(stream, transfer_id).await?;
                    return Err("Transfer canceled by user.".to_string());
                }
                if !pause_flag.load(Ordering::SeqCst) {
                    write_message(
                        stream,
                        &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
                            transfer_id.to_string(),
                            FlowControlState::Resumed,
                            FlowControlReason::User,
                        )),
                    )
                    .await?;
                    state
                        .set_transfer_status(
                            transfer_id,
                            crate::types::TransferStatus::Transferring,
                            Some("Transfer resumed.".to_string()),
                        )
                        .await;
                    emit_transfers(state, app).await?;
                    return Ok(());
                }
                match peek_then_read_message(stream, 120).await? {
                    None => {}
                    Some(ControlMessage::TransferFlowControl(resume))
                        if resume.transfer_id == transfer_id
                            && matches!(resume.state, FlowControlState::Resumed) =>
                    {
                        pause_flag.store(false, Ordering::SeqCst);
                        state
                            .set_transfer_status(
                                transfer_id,
                                crate::types::TransferStatus::Transferring,
                                Some("Peer resumed the transfer.".to_string()),
                            )
                            .await;
                        emit_transfers(state, app).await?;
                        return Ok(());
                    }
                    Some(ControlMessage::TransferFlowControl(still_paused))
                        if still_paused.transfer_id == transfer_id
                            && matches!(still_paused.state, FlowControlState::Paused) =>
                    {
                        continue;
                    }
                    Some(ControlMessage::TransferFlowControl(cancel))
                        if cancel.transfer_id == transfer_id
                            && matches!(cancel.state, FlowControlState::CancelRequested) =>
                    {
                        return Err("Transfer canceled by peer.".to_string());
                    }
                    Some(ControlMessage::TransferFlowControl(_)) => {
                        return Err("received flow control for the wrong transfer".to_string());
                    }
                    Some(_) => {
                        return Err("unexpected frame while waiting for peer resume".to_string())
                    }
                }
            }
        }
        FlowControlState::Resumed => {
            pause_flag.store(false, Ordering::SeqCst);
            state
                .set_transfer_status(
                    transfer_id,
                    crate::types::TransferStatus::Transferring,
                    Some("Peer resumed the transfer.".to_string()),
                )
                .await;
            emit_transfers(state, app).await
        }
        FlowControlState::CancelRequested => Err("Transfer canceled by peer.".to_string()),
    }
}

#[allow(dead_code)]
async fn honor_sender_pause_or_cancel(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    if cancel_flag.load(Ordering::SeqCst) {
        notify_peer_cancel(stream, transfer_id).await?;
        return Err("Transfer canceled by user.".to_string());
    }
    if !pause_flag.load(Ordering::SeqCst) {
        return Ok(());
    }

    // Drain in-flight ACKs before announcing pause so the peer pause wait
    // does not see leftover chunk acknowledgements as protocol errors.
    flush_pending_chunk_acks(
        state,
        app,
        stream,
        transfer_id,
        pending,
        cancel_flag,
        pause_flag,
    )
    .await?;
    wait_while_paused_with_peer_notice(
        state,
        app,
        stream,
        transfer_id,
        cancel_flag,
        pause_flag,
        Some(pending),
    )
    .await?;
    if cancel_flag.load(Ordering::SeqCst) {
        notify_peer_cancel(stream, transfer_id).await?;
        return Err("Transfer canceled by user.".to_string());
    }
    Ok(())
}

/// Receiver-side read that stays paused until resume, while still accepting
/// pipelined file frames that were already in flight.
#[allow(dead_code)]
async fn next_control_message_honoring_pause(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<ControlMessage, String> {
    let mut sent_pause_notice = false;
    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }

        if pause_flag.load(Ordering::SeqCst) {
            if !sent_pause_notice {
                write_message(
                    stream,
                    &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
                        transfer_id.to_string(),
                        FlowControlState::Paused,
                        FlowControlReason::User,
                    )),
                )
                .await?;
                state
                    .set_transfer_status(
                        transfer_id,
                        crate::types::TransferStatus::Paused,
                        Some("Transfer paused.".to_string()),
                    )
                    .await;
                emit_transfers(state, app).await?;
                sent_pause_notice = true;
            }

            match peek_then_read_message(stream, 120).await? {
                None => continue,
                Some(ControlMessage::TransferFlowControl(flow))
                    if flow.transfer_id == transfer_id =>
                {
                    match flow.state {
                        FlowControlState::Resumed => {
                            pause_flag.store(false, Ordering::SeqCst);
                            state
                                .set_transfer_status(
                                    transfer_id,
                                    crate::types::TransferStatus::Transferring,
                                    Some("Peer resumed the transfer.".to_string()),
                                )
                                .await;
                            emit_transfers(state, app).await?;
                            continue;
                        }
                        FlowControlState::CancelRequested => {
                            return Err("Transfer canceled by peer.".to_string());
                        }
                        FlowControlState::Paused => continue,
                    }
                }
                Some(ControlMessage::TransferFlowControl(_)) => {
                    return Err("received flow control for the wrong transfer".to_string());
                }
                Some(message) => {
                    // Stay paused, but process already-in-flight file frames.
                    return Ok(message);
                }
            }
        }

        if sent_pause_notice {
            write_message(
                stream,
                &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
                    transfer_id.to_string(),
                    FlowControlState::Resumed,
                    FlowControlReason::User,
                )),
            )
            .await?;
            state
                .set_transfer_status(
                    transfer_id,
                    crate::types::TransferStatus::Transferring,
                    Some("Transfer resumed.".to_string()),
                )
                .await;
            emit_transfers(state, app).await?;
        }

        return read_message(stream).await;
    }
}

#[allow(dead_code)]
async fn wait_while_paused_with_peer_notice(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
    mut pending: Option<&mut VecDeque<PendingChunkAck>>,
) -> Result<(), String> {
    if !pause_flag.load(Ordering::SeqCst) {
        return Ok(());
    }

    write_message(
        stream,
        &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
            transfer_id.to_string(),
            FlowControlState::Paused,
            FlowControlReason::User,
        )),
    )
    .await?;

    state
        .set_transfer_status(
            transfer_id,
            crate::types::TransferStatus::Paused,
            Some("Transfer paused.".to_string()),
        )
        .await;
    emit_transfers(state, app).await?;

    let mut resumed_by_peer = false;
    while pause_flag.load(Ordering::SeqCst) {
        if cancel_flag.load(Ordering::SeqCst) {
            write_message(
                stream,
                &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
                    transfer_id.to_string(),
                    FlowControlState::CancelRequested,
                    FlowControlReason::User,
                )),
            )
            .await?;
            return Err("Transfer canceled by user.".to_string());
        }
        match peek_then_read_message(stream, 120).await? {
            None => {}
            Some(ControlMessage::FileChunkAck(ack)) => {
                let Some(pending_acks) = pending.as_mut() else {
                    // Receiver-side pause wait has no ACK queue.
                    continue;
                };
                if ack.transfer_id == transfer_id {
                    let _ = apply_pending_chunk_ack(pending_acks, &ack.relative_path, ack.sequence);
                }
            }
            Some(ControlMessage::TransferFlowControl(flow)) if flow.transfer_id == transfer_id => {
                match flow.state {
                    FlowControlState::Resumed => {
                        pause_flag.store(false, Ordering::SeqCst);
                        resumed_by_peer = true;
                        break;
                    }
                    FlowControlState::CancelRequested => {
                        return Err("Transfer canceled by peer.".to_string());
                    }
                    FlowControlState::Paused => {}
                }
            }
            Some(ControlMessage::TransferFlowControl(_)) => {
                return Err("received flow control for the wrong transfer".to_string());
            }
            Some(_) => {
                return Err("unexpected frame while locally paused".to_string());
            }
        }
    }

    write_message(
        stream,
        &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
            transfer_id.to_string(),
            FlowControlState::Resumed,
            FlowControlReason::User,
        )),
    )
    .await?;

    state
        .set_transfer_status(
            transfer_id,
            crate::types::TransferStatus::Transferring,
            Some(if resumed_by_peer {
                "Peer resumed the transfer.".to_string()
            } else {
                "Transfer resumed.".to_string()
            }),
        )
        .await;
    emit_transfers(state, app).await
}

#[allow(dead_code)]
async fn note_chunk_sent_and_manage_ack_window(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    ack: PendingChunkAck,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    drain_ready_chunk_acks(
        state,
        app,
        stream,
        transfer_id,
        pending,
        cancel_flag,
        pause_flag,
    )
    .await?;
    pending.push_back(ack);
    while pending.len() >= CHUNK_ACK_WINDOW {
        wait_for_next_chunk_ack(
            state,
            app,
            stream,
            transfer_id,
            pending,
            cancel_flag,
            pause_flag,
        )
        .await?;
    }
    Ok(())
}

#[allow(dead_code)]
async fn flush_pending_chunk_acks(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    while !pending.is_empty() {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }
        wait_for_next_chunk_ack(
            state,
            app,
            stream,
            transfer_id,
            pending,
            cancel_flag,
            pause_flag,
        )
        .await?;
    }
    Ok(())
}

#[allow(dead_code)]
async fn drain_ready_chunk_acks(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            notify_peer_cancel(stream, transfer_id).await?;
            return Err("Transfer canceled by user.".to_string());
        }
        match peek_then_read_message(stream, 0).await? {
            None => return Ok(()),
            Some(message) => {
                apply_ack_or_flow_control_message(
                    state,
                    app,
                    stream,
                    transfer_id,
                    pending,
                    message,
                    cancel_flag,
                    pause_flag,
                )
                .await?;
            }
        }
    }
}

fn apply_pending_chunk_ack(
    pending: &mut VecDeque<PendingChunkAck>,
    relative_path: &str,
    sequence: u64,
) -> bool {
    let before = pending.len();
    // Cumulative per-file ACK: drop this sequence and any older ones for the
    // same path. This stays correct on a single TCP stream and survives
    // pause/resume races where a duplicate or slightly-reordered ACK would
    // previously fail the whole transfer with "wrong chunk".
    pending.retain(|entry| entry.relative_path != relative_path || entry.sequence > sequence);
    pending.len() < before
}

fn control_message_label(message: &ControlMessage) -> &'static str {
    match message {
        ControlMessage::FileChunkAck(_) => "file_chunk_ack",
        ControlMessage::TransferFlowControl(_) => "transfer_flow_control",
        ControlMessage::FileStart(_) => "file_start",
        ControlMessage::FileChunk(_) => "file_chunk",
        ControlMessage::FileEnd(_) => "file_end",
        ControlMessage::TransferManifest(_) => "transfer_manifest",
        ControlMessage::TransferResumePlan(_) => "transfer_resume_plan",
        ControlMessage::TransferComplete(_) => "transfer_complete",
        ControlMessage::TransferReceipt(_) => "transfer_receipt",
        ControlMessage::TransferOffer(_) => "transfer_offer",
        ControlMessage::TransferResponse(_) => "transfer_response",
        ControlMessage::PeerHelloRequest(_) => "peer_hello_request",
        ControlMessage::PeerHelloResponse(_) => "peer_hello_response",
        ControlMessage::ParallelWorkerOpen(_) => "parallel_worker_open",
        ControlMessage::ParallelWorkerReady(_) => "parallel_worker_ready",
        ControlMessage::ParallelWorkerComplete(_) => "parallel_worker_complete",
    }
}

#[allow(dead_code)]
async fn wait_for_next_chunk_ack(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    let before = pending.len();
    if before == 0 {
        return Ok(());
    }
    loop {
        if cancel_flag.load(Ordering::SeqCst) {
            write_message(
                stream,
                &ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
                    transfer_id.to_string(),
                    FlowControlState::CancelRequested,
                    FlowControlReason::User,
                )),
            )
            .await?;
            return Err("Transfer canceled by user.".to_string());
        }

        match peek_then_read_message(stream, 120).await? {
            None => continue,
            Some(message) => {
                apply_ack_or_flow_control_message(
                    state,
                    app,
                    stream,
                    transfer_id,
                    pending,
                    message,
                    cancel_flag,
                    pause_flag,
                )
                .await?;
                // Peer pause may drain several ACKs at once; any shrink (or empty
                // queue) means this wait is satisfied.
                if pending.len() < before || pending.is_empty() {
                    return Ok(());
                }
            }
        }
    }
}

/// Handles one inbound frame during chunk ACK waiting.
/// Returns `Ok(true)` when a pending chunk ACK was consumed.
#[allow(dead_code)]
async fn apply_ack_or_flow_control_message(
    state: &AppState,
    app: &tauri::AppHandle,
    stream: &mut TcpStream,
    transfer_id: &str,
    pending: &mut VecDeque<PendingChunkAck>,
    message: ControlMessage,
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<bool, String> {
    match message {
        ControlMessage::FileChunkAck(ack) => {
            if ack.transfer_id != transfer_id {
                return Err("receiver acknowledged the wrong transfer".to_string());
            }
            Ok(apply_pending_chunk_ack(
                pending,
                &ack.relative_path,
                ack.sequence,
            ))
        }
        ControlMessage::TransferFlowControl(flow) if flow.transfer_id == transfer_id => {
            match flow.state {
                FlowControlState::Paused => {
                    pause_flag.store(true, Ordering::SeqCst);
                    state
                        .set_transfer_status(
                            transfer_id,
                            crate::types::TransferStatus::Paused,
                            Some("Peer paused the transfer.".to_string()),
                        )
                        .await;
                    emit_transfers(state, app).await?;
                    let mut consumed_ack = false;
                    loop {
                        if cancel_flag.load(Ordering::SeqCst) {
                            write_message(
                                stream,
                                &ControlMessage::TransferFlowControl(
                                    TransferFlowControlMessage::new(
                                        transfer_id.to_string(),
                                        FlowControlState::CancelRequested,
                                        FlowControlReason::User,
                                    ),
                                ),
                            )
                            .await?;
                            return Err("Transfer canceled by user.".to_string());
                        }
                        if !pause_flag.load(Ordering::SeqCst) {
                            write_message(
                                stream,
                                &ControlMessage::TransferFlowControl(
                                    TransferFlowControlMessage::new(
                                        transfer_id.to_string(),
                                        FlowControlState::Resumed,
                                        FlowControlReason::User,
                                    ),
                                ),
                            )
                            .await?;
                            break;
                        }
                        match peek_then_read_message(stream, 120).await? {
                            None => {}
                            Some(ControlMessage::FileChunkAck(ack)) => {
                                if ack.transfer_id == transfer_id
                                    && apply_pending_chunk_ack(
                                        pending,
                                        &ack.relative_path,
                                        ack.sequence,
                                    )
                                {
                                    consumed_ack = true;
                                }
                            }
                            Some(ControlMessage::TransferFlowControl(resume))
                                if resume.transfer_id == transfer_id
                                    && matches!(resume.state, FlowControlState::Resumed) =>
                            {
                                pause_flag.store(false, Ordering::SeqCst);
                                break;
                            }
                            Some(ControlMessage::TransferFlowControl(still_paused))
                                if still_paused.transfer_id == transfer_id
                                    && matches!(still_paused.state, FlowControlState::Paused) =>
                            {
                                continue;
                            }
                            Some(ControlMessage::TransferFlowControl(cancel))
                                if cancel.transfer_id == transfer_id
                                    && matches!(
                                        cancel.state,
                                        FlowControlState::CancelRequested
                                    ) =>
                            {
                                return Err("Transfer canceled by peer.".to_string());
                            }
                            Some(_) => {
                                return Err(
                                    "unexpected frame while waiting for peer resume".to_string()
                                );
                            }
                        }
                    }
                    pause_flag.store(false, Ordering::SeqCst);
                    // Drain any ACKs that arrived during the resume handshake
                    // before the sender continues pushing new chunks.
                    loop {
                        match peek_then_read_message(stream, 0).await? {
                            None => break,
                            Some(ControlMessage::FileChunkAck(ack))
                                if ack.transfer_id == transfer_id =>
                            {
                                let _ = apply_pending_chunk_ack(
                                    pending,
                                    &ack.relative_path,
                                    ack.sequence,
                                );
                                consumed_ack = true;
                            }
                            Some(ControlMessage::TransferFlowControl(flow))
                                if flow.transfer_id == transfer_id
                                    && matches!(
                                        flow.state,
                                        FlowControlState::Resumed | FlowControlState::Paused
                                    ) =>
                            {
                                // Idempotent pause/resume chatter after resume.
                                continue;
                            }
                            Some(ControlMessage::TransferFlowControl(cancel))
                                if cancel.transfer_id == transfer_id
                                    && matches!(
                                        cancel.state,
                                        FlowControlState::CancelRequested
                                    ) =>
                            {
                                return Err("Transfer canceled by peer.".to_string());
                            }
                            Some(other) => {
                                // Put unexpected frames back into the normal wait path
                                // by applying them once more below via a one-shot handle.
                                // For safety, only ACKs/flow-control should be here.
                                return Err(format!(
                                    "unexpected frame while draining after resume: {}",
                                    control_message_label(&other)
                                ));
                            }
                        }
                    }
                    state
                        .set_transfer_status(
                            transfer_id,
                            crate::types::TransferStatus::Transferring,
                            Some("Transfer resumed.".to_string()),
                        )
                        .await;
                    emit_transfers(state, app).await?;
                    Ok(consumed_ack || pending.is_empty())
                }
                FlowControlState::Resumed => Ok(false),
                FlowControlState::CancelRequested => Err("Transfer canceled by peer.".to_string()),
            }
        }
        ControlMessage::TransferFlowControl(_) => {
            Err("received flow control for the wrong transfer".to_string())
        }
        _ => Err("expected chunk acknowledgement from receiver".to_string()),
    }
}

pub(crate) fn configure_transfer_socket(stream: &TcpStream) {
    if let Err(error) = stream.set_nodelay(true) {
        tracing::debug!("failed to enable TCP_NODELAY: {error}");
    }
    // Large socket buffers so high-throughput links (Wi-Fi Direct, fast LAN)
    // run at full speed from the first byte instead of ramping up slowly
    // behind OS-default (often 64-256 KiB) windows. Best-effort: the OS may
    // clamp the values, which is fine.
    const TRANSFER_SOCKET_BUFFER_SIZE: usize = 4 * 1024 * 1024;
    let socket = socket2::SockRef::from(stream);
    if let Err(error) = socket.set_send_buffer_size(TRANSFER_SOCKET_BUFFER_SIZE) {
        tracing::debug!("failed to grow TCP send buffer: {error}");
    }
    if let Err(error) = socket.set_recv_buffer_size(TRANSFER_SOCKET_BUFFER_SIZE) {
        tracing::debug!("failed to grow TCP receive buffer: {error}");
    }
}

#[allow(dead_code)]
async fn wait_while_paused(
    cancel_flag: &Arc<AtomicBool>,
    pause_flag: &Arc<AtomicBool>,
) -> Result<(), String> {
    while pause_flag.load(Ordering::SeqCst) {
        if cancel_flag.load(Ordering::SeqCst) {
            return Err("Transfer canceled by user.".to_string());
        }
        sleep(Duration::from_millis(120)).await;
    }
    Ok(())
}

async fn joined_active_files(
    active_files: &AsyncMutex<HashMap<u16, String>>,
    fallback: &str,
) -> String {
    let files = active_files.lock().await;
    let mut entries = files.iter().collect::<Vec<_>>();
    entries.sort_by_key(|(worker_id, _)| **worker_id);
    if entries.is_empty() {
        fallback.to_string()
    } else {
        entries
            .into_iter()
            .map(|(_, path)| path.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    }
}

async fn maybe_emit_progress(
    state: &AppState,
    app: &tauri::AppHandle,
    transfer_id: &str,
    total_bytes: u64,
    completed_bytes: u64,
    current_file: &str,
    started_at_ms: u64,
    last_emit_ms: &mut u64,
) -> Result<(), String> {
    let now = now_ms();
    if now.saturating_sub(*last_emit_ms) < PROGRESS_EMIT_INTERVAL_MS
        && completed_bytes < total_bytes
    {
        return Ok(());
    }

    *last_emit_ms = now;
    emit_progress(
        state,
        app,
        transfer_id,
        total_bytes,
        completed_bytes,
        current_file,
        started_at_ms,
    )
    .await
}

async fn maybe_emit_parallel_progress(
    state: &AppState,
    app: &tauri::AppHandle,
    transfer_id: &str,
    total_bytes: u64,
    completed_bytes: u64,
    current_file: &str,
    started_at_ms: u64,
    last_emit_ms: &AsyncMutex<u64>,
) -> Result<(), String> {
    let now = now_ms();
    let mut last_emit = last_emit_ms.lock().await;
    if now.saturating_sub(*last_emit) < PROGRESS_EMIT_INTERVAL_MS && completed_bytes < total_bytes {
        return Ok(());
    }

    *last_emit = now;
    drop(last_emit);
    emit_progress(
        state,
        app,
        transfer_id,
        total_bytes,
        completed_bytes,
        current_file,
        started_at_ms,
    )
    .await
}

async fn emit_progress(
    state: &AppState,
    app: &tauri::AppHandle,
    transfer_id: &str,
    total_bytes: u64,
    completed_bytes: u64,
    current_file: &str,
    started_at_ms: u64,
) -> Result<(), String> {
    let elapsed_ms = now_ms().saturating_sub(started_at_ms).max(1);
    let elapsed_seconds = elapsed_ms as f64 / 1000.0;
    let bits_per_second = (completed_bytes as f64 * 8.0) / elapsed_seconds;
    let speed_mbps = if completed_bytes == 0 {
        None
    } else {
        Some((bits_per_second / 1_000_000.0) as f32)
    };
    let bytes_per_second = completed_bytes as f64 / elapsed_seconds;
    let eta_seconds = if completed_bytes == 0 || completed_bytes >= total_bytes {
        None
    } else {
        Some(((total_bytes - completed_bytes) as f64 / bytes_per_second).ceil() as u64)
    };

    state
        .update_transfer_progress(
            transfer_id,
            completed_bytes,
            Some(current_file.to_string()),
            speed_mbps,
            eta_seconds,
        )
        .await;
    emit_transfers(state, app).await
}

async fn emit_transfers(state: &AppState, app: &tauri::AppHandle) -> Result<(), String> {
    events::emit_transfers_updated(app, &state.transfers().await)
}

async fn build_resume_plan(
    manifest: &TransferManifestMessage,
    destination_root: &Path,
) -> Result<Vec<TransferResumeFileState>, String> {
    let mut files = Vec::new();

    for entry in manifest
        .entries
        .iter()
        .filter(|entry| matches!(entry.kind, TransferManifestEntryKind::File))
    {
        let expected_size = entry.size.unwrap_or(0);
        let output_path = resolve_destination_path(destination_root, &entry.relative_path, true)?;
        let temporary_output_path = temporary_output_path(&output_path);

        let (completed_bytes, complete) = if let Ok(metadata) = fs::metadata(&output_path).await {
            let len = metadata.len();
            if len == expected_size {
                (expected_size, true)
            } else {
                let _ = fs::remove_file(&output_path).await;
                (0, false)
            }
        } else if let Ok(metadata) = fs::metadata(&temporary_output_path).await {
            (metadata.len().min(expected_size), false)
        } else {
            (0, false)
        };

        files.push(TransferResumeFileState {
            relative_path: entry.relative_path.clone(),
            completed_bytes,
            complete,
        });
    }

    Ok(files)
}

fn build_destination_root(base: &Path, peer_name: &str, transfer_id: &str) -> PathBuf {
    let _ = (peer_name, transfer_id);
    base.join("LanDrop")
}

fn temporary_output_path(output_path: &Path) -> PathBuf {
    let file_name = output_path
        .file_name()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    output_path.with_file_name(format!("{file_name}.landrop-part"))
}

fn resolve_destination_path(
    base: &Path,
    relative_path: &str,
    is_file: bool,
) -> Result<PathBuf, String> {
    let mut destination = base.join(if is_file {
        category_for_path(relative_path)
    } else {
        "Files"
    });
    for component in Path::new(relative_path).components() {
        match component {
            Component::Normal(segment) => {
                destination.push(sanitize_segment(&segment.to_string_lossy()));
            }
            Component::CurDir => {}
            _ => {
                return Err(format!(
                    "refusing unsafe destination path {}",
                    relative_path
                ));
            }
        }
    }

    Ok(destination)
}

fn category_for_path(path: &str) -> &'static str {
    let extension = Path::new(path)
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();

    match extension.as_str() {
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "bmp" | "heic" | "heif" => "Images",
        "mp4" | "mkv" | "mov" | "avi" | "webm" | "m4v" => "Videos",
        "mp3" | "wav" | "flac" | "aac" | "m4a" | "ogg" => "Music",
        "zip" | "rar" | "7z" | "tar" | "gz" | "bz2" | "xz" => "Compressed",
        "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt" | "md" | "rtf"
        | "epub" => "Documents",
        _ => "Files",
    }
}

fn sanitize_segment(value: &str) -> String {
    let sanitized = value
        .chars()
        .map(|ch| match ch {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            value if value.is_control() => '_',
            value => value,
        })
        .collect::<String>()
        .trim()
        .trim_end_matches('.')
        .trim_end_matches(' ')
        .to_string();

    if sanitized.is_empty() {
        "Transfer".to_string()
    } else {
        sanitized
    }
}
