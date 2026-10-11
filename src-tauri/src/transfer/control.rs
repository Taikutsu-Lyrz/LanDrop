use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use tokio::net::{TcpListener, TcpStream};
use tokio::time::{timeout, Duration};
use tracing::{debug, warn};
use uuid::Uuid;

use crate::app::AppState;
use crate::config::settings::QuickSaveMode;
use crate::discovery::adapter::AdapterKind;
use crate::events;
use crate::notifications;
use crate::transfer::error::{is_cancellation_error, transfer_error_message, PEER_CANCELED};
use crate::transfer::protocol::{
    read_message, write_message, ControlMessage, FlowControlState, ParallelWorkerOpenMessage,
    ParallelWorkerReadyMessage, PeerHelloRequestMessage, PeerHelloResponseMessage,
    TransferEncoding, TransferOfferMessage, TransferReceiptMessage, TransferResponseMessage,
};
use crate::transfer::stream;
use crate::types::{
    now_ms, IncomingTransferDecision, PeerDevice, SendDraft, TransferDirection, TransferStatus,
    TransferSummary,
};

const OFFER_RESPONSE_TIMEOUT: Duration = Duration::from_secs(300);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const MANUAL_PEER_HELLO_TIMEOUT: Duration = Duration::from_secs(20);
const RECEIPT_TIMEOUT: Duration = Duration::from_secs(30);
const CANCELED_MESSAGE: &str = "Transfer canceled by user.";

pub async fn run_listener(state: AppState, app: tauri::AppHandle) {
    let port = {
        let bootstrap = state.bootstrap().await;
        bootstrap.settings.tcp_port
    };

    let listener = match TcpListener::bind(("0.0.0.0", port)).await {
        Ok(listener) => listener,
        Err(error) => {
            warn!("unable to bind TCP control listener on {port}: {error}");
            return;
        }
    };

    loop {
        match listener.accept().await {
            Ok((stream, from)) => {
                let state = state.clone();
                let app = app.clone();
                stream::configure_transfer_socket(&stream);
                tauri::async_runtime::spawn(async move {
                    if let Err(error) = handle_connection(state, app, stream, from).await {
                        debug!("control connection ended with error: {error}");
                    }
                });
            }
            Err(error) => {
                warn!("control listener accept failed: {error}");
            }
        }
    }
}

pub async fn send_offer(
    state: AppState,
    app: tauri::AppHandle,
    target_device_id: String,
    pair_code: Option<String>,
) -> Result<TransferSummary, String> {
    let draft = state
        .send_draft()
        .await
        .ok_or_else(|| "no staged send is available".to_string())?;
    send_offer_with_draft(state, app, target_device_id, pair_code, draft).await
}

pub async fn retry_transfer(
    state: AppState,
    app: tauri::AppHandle,
    transfer_id: String,
) -> Result<TransferSummary, String> {
    let (peer_device_id, pair_code, draft) = state
        .retry_context(&transfer_id)
        .await
        .ok_or_else(|| "no retry context exists for this transfer".to_string())?;
    send_offer_with_draft_with_id(state, app, peer_device_id, pair_code, draft, transfer_id).await
}

pub async fn probe_manual_peer(
    state: AppState,
    app: tauri::AppHandle,
    ip: String,
    port: Option<u16>,
) -> Result<PeerDevice, String> {
    let fallback_port = state.bootstrap().await.settings.tcp_port;
    let target_port = port.unwrap_or(fallback_port);
    let target = format!("{ip}:{target_port}");
    let mut stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(&target))
        .await
        .map_err(|_| format!("connection to {target} timed out"))?
        .map_err(|error| error.to_string())?;
    stream::configure_transfer_socket(&stream);

    write_message(
        &mut stream,
        &ControlMessage::PeerHelloRequest(PeerHelloRequestMessage::new()),
    )
    .await?;

    let response = timeout(MANUAL_PEER_HELLO_TIMEOUT, read_message(&mut stream))
        .await
        .map_err(|_| "manual peer did not respond in time".to_string())??;

    match response {
        ControlMessage::PeerHelloResponse(response) => {
            let peer = PeerDevice {
                device_id: response.device_id.clone(),
                instance_id: response.instance_id,
                name: response.device_name.clone(),
                device_kind: response.device_kind,
                ip: ip.clone(),
                port: target_port,
                adapter_kind: AdapterKind::Unknown,
                availability: response.availability,
                requires_pair_code: response.requires_pair_code,
                last_seen_ms: now_ms(),
                priority: 30,
            };
            state.add_or_replace_manual_peer(peer.clone()).await;
            // A manual connection is an explicit user action (IP typed in or
            // Tailscale/VPN address entered). Remember it as trusted so
            // Favorites quick-save and the trusted list keep working.
            // Loopback (127.x / ::1) is the local remote-tunnel endpoint, not
            // the real peer address, so it must never become a trusted IP.
            if !is_loopback_ip(&ip) {
                state
                    .trust_device(&response.device_id, &response.device_name, &ip)
                    .await;
                let _ = events::emit_settings_updated(&app, &state.bootstrap().await.settings);
            }
            let peers = state.peers().await;
            events::emit_devices_updated(&app, &peers)?;
            let _ = notifications::notify_info(
                &app,
                "Manual peer added",
                &format!("{} is ready at {}:{}", peer.name, peer.ip, peer.port),
            );
            Ok(peer)
        }
        _ => Err("manual peer returned an unexpected control frame".to_string()),
    }
}

pub async fn respond_to_offer(
    state: AppState,
    app: tauri::AppHandle,
    transfer_id: String,
    accept: bool,
    save_path: Option<String>,
) -> Result<(), String> {
    state
        .respond_to_pending_incoming(&transfer_id, IncomingTransferDecision { accept, save_path })
        .await?;
    if !accept {
        state.set_incoming_offer(None).await;
        events::emit_incoming_transfer(&app, &None)?;
    }
    Ok(())
}

pub async fn cancel_transfer(
    state: AppState,
    app: tauri::AppHandle,
    transfer_id: String,
) -> Result<(), String> {
    state.cancel_transfer(&transfer_id).await?;
    state
        .set_transfer_status(
            &transfer_id,
            TransferStatus::Canceled,
            Some("Cancel requested. LanDrop is stopping the transfer.".to_string()),
        )
        .await;
    emit_transfers(&state, &app).await?;
    Ok(())
}

pub async fn set_transfer_paused(
    state: AppState,
    app: tauri::AppHandle,
    transfer_id: String,
    paused: bool,
) -> Result<(), String> {
    state.set_transfer_paused(&transfer_id, paused).await?;
    state
        .set_transfer_status(
            &transfer_id,
            if paused {
                TransferStatus::Paused
            } else {
                TransferStatus::Transferring
            },
            Some(if paused {
                "Transfer paused.".to_string()
            } else {
                "Transfer resumed.".to_string()
            }),
        )
        .await;
    emit_transfers(&state, &app).await
}

async fn send_offer_with_draft(
    state: AppState,
    app: tauri::AppHandle,
    target_device_id: String,
    pair_code: Option<String>,
    draft: SendDraft,
) -> Result<TransferSummary, String> {
    let transfer_id = Uuid::new_v4().to_string();
    send_offer_with_draft_with_id(state, app, target_device_id, pair_code, draft, transfer_id).await
}

async fn send_offer_with_draft_with_id(
    state: AppState,
    app: tauri::AppHandle,
    target_device_id: String,
    pair_code: Option<String>,
    draft: SendDraft,
    transfer_id: String,
) -> Result<TransferSummary, String> {
    let peer = state
        .peer_by_id(&target_device_id)
        .await
        .ok_or_else(|| "selected device is no longer available".to_string())?;
    let identity = state.discovery_identity().await;
    let max_parallel_file_streams = state.bootstrap().await.settings.concurrent_file_streams;

    let offer = TransferOfferMessage::from_send_draft(
        transfer_id.clone(),
        identity.device_id,
        identity.device_name,
        Some(peer.device_id.clone()),
        pair_code.clone(),
        &draft,
        max_parallel_file_streams,
    );

    let summary = TransferSummary {
        transfer_id: transfer_id.clone(),
        peer_name: peer.name.clone(),
        direction: TransferDirection::Outgoing,
        status: TransferStatus::AwaitingAccept,
        file_count: draft.file_count,
        total_bytes: draft.total_bytes,
        completed_bytes: 0,
        progress_pct: 0.0,
        current_file: Some("Waiting for receiver approval".to_string()),
        speed_mbps: None,
        eta_seconds: None,
    };

    state.upsert_transfer(summary.clone()).await;
    emit_transfers(&state, &app).await?;
    let (cancel_flag, pause_flag) = state
        .register_outgoing_transfer(
            transfer_id.clone(),
            peer.device_id.clone(),
            pair_code,
            draft.clone(),
        )
        .await;

    let draft_for_task = draft.clone();
    let transfer_id_for_task = transfer_id.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(error) = run_outgoing_offer(
            state.clone(),
            app.clone(),
            peer.clone(),
            draft_for_task,
            offer,
            cancel_flag,
            pause_flag,
        )
        .await
        {
            let status = if is_cancellation_error(&error) {
                TransferStatus::Canceled
            } else {
                TransferStatus::Failed
            };
            state
                .set_transfer_status(&transfer_id_for_task, status, Some(error.clone()))
                .await;
            state.finish_transfer(&transfer_id_for_task).await;
            let _ = emit_transfers(&state, &app).await;
            if !is_cancellation_error(&error) {
                let _ = notifications::notify_transfer_issue(
                    &app,
                    "Transfer stopped",
                    &format!("{}: {}", peer.name, error),
                );
            }
            debug!("outgoing offer task ended with error: {error}");
        }
    });

    Ok(summary)
}

async fn run_outgoing_offer(
    state: AppState,
    app: tauri::AppHandle,
    peer: PeerDevice,
    draft: SendDraft,
    offer: TransferOfferMessage,
    cancel_flag: Arc<AtomicBool>,
    pause_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    let transfer_id = offer.transfer_id.clone();
    if cancel_flag.load(Ordering::SeqCst) {
        return Err(CANCELED_MESSAGE.to_string());
    }
    let target = format!("{}:{}", peer.ip, peer.port);
    let mut stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(&target))
        .await
        .map_err(|_| format!("connection to {} timed out", peer.name))?
        .map_err(|error| error.to_string())?;
    stream::configure_transfer_socket(&stream);

    write_message(&mut stream, &ControlMessage::TransferOffer(offer)).await?;

    let response = read_offer_response(&mut stream, &cancel_flag).await?;

    match response {
        ControlMessage::TransferResponse(response) => {
            if response.accepted {
                let selected_transfer_encoding = response
                    .selected_transfer_encoding
                    .unwrap_or(TransferEncoding::RawV1);
                let selected_parallel_file_streams = response.selected_parallel_file_streams.max(1);
                if cancel_flag.load(Ordering::SeqCst) {
                    return Err(CANCELED_MESSAGE.to_string());
                }

                state
                    .set_transfer_status(
                        &transfer_id,
                        TransferStatus::Accepted,
                        response.message.or(Some(
                            "Receiver approved. Streaming is starting.".to_string(),
                        )),
                    )
                    .await;
                emit_transfers(&state, &app).await?;

                state
                    .set_transfer_status(
                        &transfer_id,
                        TransferStatus::Transferring,
                        Some("Streaming files over the LAN".to_string()),
                    )
                    .await;
                emit_transfers(&state, &app).await?;

                match selected_transfer_encoding {
                    TransferEncoding::FramedV2 => {
                        stream::send_transfer_stream_framed_v2(
                            &state,
                            &app,
                            &mut stream,
                            &transfer_id,
                            &draft,
                            target.clone(),
                            selected_parallel_file_streams,
                            cancel_flag.clone(),
                            pause_flag.clone(),
                        )
                        .await?;
                    }
                    TransferEncoding::RawV1 => {
                        stream::send_transfer_stream(
                            &state,
                            &app,
                            &mut stream,
                            &transfer_id,
                            &draft,
                            1,
                            cancel_flag.clone(),
                            pause_flag.clone(),
                        )
                        .await?;
                    }
                }

                state
                    .set_transfer_status(
                        &transfer_id,
                        TransferStatus::Transferring,
                        Some("Waiting for receiver confirmation".to_string()),
                    )
                    .await;
                emit_transfers(&state, &app).await?;

                let receipt = timeout(RECEIPT_TIMEOUT, read_message(&mut stream))
                    .await
                    .map_err(|_| {
                        "receiver did not confirm transfer completion in time".to_string()
                    })??;

                match receipt {
                    ControlMessage::TransferReceipt(receipt)
                        if receipt.transfer_id == transfer_id =>
                    {
                        if receipt.success {
                            state
                                .set_transfer_status(
                                    &transfer_id,
                                    TransferStatus::Completed,
                                    Some(
                                        receipt
                                            .message
                                            .or_else(|| {
                                                receipt.saved_into.map(|path| {
                                                    format!("Receiver saved into {}", path)
                                                })
                                            })
                                            .unwrap_or_else(|| "Transfer completed".to_string()),
                                    ),
                                )
                                .await;
                            let _ = notifications::notify_info(
                                &app,
                                "Transfer complete",
                                &format!("{} confirmed the transfer.", peer.name),
                            );
                        } else {
                            let message = receipt.message.unwrap_or_else(|| {
                                "Receiver reported a transfer failure.".to_string()
                            });
                            let canceled = is_cancellation_error(&message);
                            state
                                .set_transfer_status(
                                    &transfer_id,
                                    if canceled {
                                        TransferStatus::Canceled
                                    } else {
                                        TransferStatus::Failed
                                    },
                                    Some(message),
                                )
                                .await;
                            if !canceled {
                                let _ = notifications::notify_transfer_issue(
                                    &app,
                                    "Transfer failed",
                                    &format!("{} reported a receive-side error.", peer.name),
                                );
                            }
                        }
                        state.finish_transfer(&transfer_id).await;
                        emit_transfers(&state, &app).await?;
                    }
                    ControlMessage::TransferReceipt(_) => {
                        return Err("receiver confirmed the wrong transfer".to_string());
                    }
                    _ => return Err("receiver returned an unexpected completion frame".to_string()),
                }
            } else {
                state
                    .set_transfer_status(
                        &transfer_id,
                        TransferStatus::Rejected,
                        response
                            .message
                            .or(Some("Receiver rejected the transfer.".to_string())),
                    )
                    .await;
                state.finish_transfer(&transfer_id).await;
                emit_transfers(&state, &app).await?;
            }
        }
        _ => {
            state
                .set_transfer_status(
                    &transfer_id,
                    TransferStatus::Failed,
                    Some("Peer returned an unexpected control frame.".to_string()),
                )
                .await;
            state.finish_transfer(&transfer_id).await;
            emit_transfers(&state, &app).await?;
        }
    }

    Ok(())
}

async fn read_offer_response(
    stream: &mut TcpStream,
    cancel_flag: &AtomicBool,
) -> Result<ControlMessage, String> {
    tokio::select! {
        biased;
        _ = async {
            while !cancel_flag.load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        } => Err(CANCELED_MESSAGE.to_string()),
        response = timeout(OFFER_RESPONSE_TIMEOUT, read_message(stream)) => {
            response.map_err(|_| "receiver did not answer in time".to_string())?
        }
    }
}

async fn handle_connection(
    state: AppState,
    app: tauri::AppHandle,
    mut stream: TcpStream,
    from: SocketAddr,
) -> Result<(), String> {
    match timeout(Duration::from_secs(10), read_message(&mut stream))
        .await
        .map_err(|_| "timed out waiting for control frame".to_string())??
    {
        ControlMessage::PeerHelloRequest(_) => {
            handle_peer_hello_request(state, &mut stream).await?;
        }
        ControlMessage::TransferOffer(offer) => {
            handle_incoming_offer(state, app, &mut stream, offer, from.ip().to_string()).await?;
        }
        ControlMessage::ParallelWorkerOpen(open) => {
            handle_parallel_worker_open(state, app, &mut stream, open).await?;
        }
        ControlMessage::PeerHelloResponse(_)
        | ControlMessage::TransferResponse(_)
        | ControlMessage::TransferManifest(_)
        | ControlMessage::TransferResumePlan(_)
        | ControlMessage::FileStart(_)
        | ControlMessage::FileChunk(_)
        | ControlMessage::FileChunkAck(_)
        | ControlMessage::FileEnd(_)
        | ControlMessage::ParallelWorkerReady(_)
        | ControlMessage::ParallelWorkerComplete(_)
        | ControlMessage::TransferFlowControl(_)
        | ControlMessage::TransferComplete(_)
        | ControlMessage::TransferReceipt(_) => {
            return Err("unexpected transfer response on server listener".to_string());
        }
    }

    Ok(())
}

async fn handle_parallel_worker_open(
    state: AppState,
    app: tauri::AppHandle,
    stream: &mut TcpStream,
    open: ParallelWorkerOpenMessage,
) -> Result<(), String> {
    let Some(session) = state.parallel_incoming_session(&open.transfer_id).await else {
        let _ = write_message(
            stream,
            &ControlMessage::ParallelWorkerReady(ParallelWorkerReadyMessage::rejected(
                open.transfer_id,
                open.worker_id,
                Some("No active parallel transfer session.".to_string()),
            )),
        )
        .await;
        return Ok(());
    };

    write_message(
        stream,
        &ControlMessage::ParallelWorkerReady(ParallelWorkerReadyMessage::accepted(
            open.transfer_id.clone(),
            open.worker_id,
            Some("Worker accepted.".to_string()),
        )),
    )
    .await?;

    match stream::receive_parallel_worker_framed_v2(
        &state,
        &app,
        stream,
        session.clone(),
        open.worker_id,
    )
    .await
    {
        Ok(()) => {
            session.mark_worker_finished();
            Ok(())
        }
        Err(error) => {
            session.set_error(error.clone()).await;
            session.mark_worker_finished();
            Err(error)
        }
    }
}

async fn handle_incoming_offer(
    state: AppState,
    app: tauri::AppHandle,
    stream: &mut TcpStream,
    offer: TransferOfferMessage,
    sender_ip: String,
) -> Result<(), String> {
    let bootstrap = state.bootstrap().await;
    if !bootstrap.settings.receive_enabled {
        let response = TransferResponseMessage::rejected(
            offer.transfer_id,
            Some("Receive mode is currently paused.".to_string()),
        );
        write_message(stream, &ControlMessage::TransferResponse(response)).await?;
        return Ok(());
    }

    if bootstrap.settings.require_pair_code
        && offer.pair_code.as_deref() != Some(bootstrap.settings.pair_code.as_str())
    {
        let response = TransferResponseMessage::rejected(
            offer.transfer_id,
            Some("Pair code required or invalid.".to_string()),
        );
        write_message(stream, &ControlMessage::TransferResponse(response)).await?;
        let _ = notifications::notify_transfer_issue(
            &app,
            "Transfer blocked",
            "An incoming transfer was rejected because the pair code was missing or invalid.",
        );
        return Ok(());
    }

    let selected_transfer_encoding =
        select_transfer_encoding(offer.supported_transfer_encodings.as_deref());
    let offered_parallel_file_streams = offer.max_parallel_file_streams.max(1);
    // A remote TLS session is relayed into this listener from loopback. Keeping
    // it to one file stream avoids a worker-connection race without reducing
    // LAN transfer concurrency.
    let is_remote_tunnel = sender_ip
        .parse::<IpAddr>()
        .is_ok_and(|address| address.is_loopback());
    let selected_parallel_file_streams =
        if selected_transfer_encoding == TransferEncoding::FramedV2 && !is_remote_tunnel {
            offered_parallel_file_streams
                .min(bootstrap.settings.concurrent_file_streams.max(1))
                .max(1)
        } else {
            1
        };
    let incoming_offer = offer.into_incoming_offer();
    let quick_save_destination = match bootstrap.settings.quick_save_mode {
        QuickSaveMode::On => bootstrap.settings.default_receive_folder.clone(),
        QuickSaveMode::Favorites
            if bootstrap
                .settings
                .trusted_device_ids
                .iter()
                .any(|device_id| device_id == &incoming_offer.sender_device_id) =>
        {
            bootstrap.settings.default_receive_folder.clone()
        }
        QuickSaveMode::Off | QuickSaveMode::Favorites => None,
    };
    let auto_accept = quick_save_destination.is_some();

    state
        .set_incoming_offer(if auto_accept {
            None
        } else {
            Some(incoming_offer.clone())
        })
        .await;
    let decision_rx = if auto_accept {
        None
    } else {
        Some(
            state
                .register_pending_incoming(incoming_offer.transfer_id.clone())
                .await,
        )
    };

    let summary = TransferSummary {
        transfer_id: incoming_offer.transfer_id.clone(),
        peer_name: incoming_offer.sender_name.clone(),
        direction: TransferDirection::Incoming,
        status: TransferStatus::AwaitingAccept,
        file_count: incoming_offer.file_count,
        total_bytes: incoming_offer.total_bytes,
        completed_bytes: 0,
        progress_pct: 0.0,
        current_file: Some("Waiting for your response".to_string()),
        speed_mbps: None,
        eta_seconds: None,
    };

    state.upsert_transfer(summary).await;
    events::emit_incoming_transfer(
        &app,
        &if auto_accept {
            None
        } else {
            Some(incoming_offer.clone())
        },
    )?;
    emit_transfers(&state, &app).await?;
    let _ = notifications::notify_info(
        &app,
        "Incoming transfer",
        &format!(
            "{} wants to send files to this PC.",
            incoming_offer.sender_name
        ),
    );

    let (accepted, response_message, receiver_status, destination_path) = if let Some(save_path) =
        quick_save_destination
    {
        (
            true,
            "Receiver approved the transfer automatically.".to_string(),
            format!("Quick Save into {}", save_path),
            Some(save_path),
        )
    } else {
        let decision = timeout(
            OFFER_RESPONSE_TIMEOUT,
            wait_incoming_decision(
                stream,
                &incoming_offer.transfer_id,
                decision_rx.expect("manual approval receiver missing"),
            ),
        )
        .await;
        if let Ok(Err(error)) = decision {
            state
                .cancel_pending_incoming(&incoming_offer.transfer_id)
                .await;
            if state
                .clear_incoming_offer(&incoming_offer.transfer_id)
                .await
            {
                events::emit_incoming_transfer(&app, &None)?;
            }
            return finish_failed_incoming(&state, &app, &incoming_offer.transfer_id, error, false)
                .await;
        }
        match decision {
            Ok(Ok(IncomingTransferDecision {
                accept: true,
                save_path: Some(save_path),
            })) => (
                true,
                "Receiver approved the transfer.".to_string(),
                format!("Ready to save into {}", save_path),
                Some(save_path),
            ),
            Ok(Ok(IncomingTransferDecision {
                accept: true,
                save_path: None,
            })) => (
                false,
                "Receiver approval needs a destination folder.".to_string(),
                "Approval was canceled because no save folder was chosen.".to_string(),
                None,
            ),
            Ok(Ok(IncomingTransferDecision {
                accept: false,
                save_path: _,
            })) => (
                false,
                "Receiver rejected the transfer.".to_string(),
                "Receiver rejected the transfer.".to_string(),
                None,
            ),
            Ok(Err(_)) => (
                false,
                "Incoming approval flow closed before a response was received.".to_string(),
                "Incoming approval flow closed before a response was received.".to_string(),
                None,
            ),
            Err(_) => (
                false,
                "Receiver did not answer the transfer request in time.".to_string(),
                "Receiver did not answer the transfer request in time.".to_string(),
                None,
            ),
        }
    };
    state
        .cancel_pending_incoming(&incoming_offer.transfer_id)
        .await;

    let response = if accepted {
        // The remote TLS tunnel relays into this listener from loopback, so
        // 127.x / ::1 here is the tunnel endpoint, not the real peer. Trust
        // is recorded on the tunnel initiator side (overlay host) instead.
        if !is_loopback_ip(&sender_ip) {
            state
                .trust_device(
                    &incoming_offer.sender_device_id,
                    &incoming_offer.sender_name,
                    &sender_ip,
                )
                .await;
        }
        events::emit_settings_updated(&app, &state.bootstrap().await.settings)?;
        state
            .set_transfer_status(
                &incoming_offer.transfer_id,
                TransferStatus::Accepted,
                Some(receiver_status),
            )
            .await;
        TransferResponseMessage::accepted_with_encoding(
            incoming_offer.transfer_id.clone(),
            Some(response_message),
            selected_transfer_encoding,
            selected_parallel_file_streams,
        )
    } else {
        state
            .set_transfer_status(
                &incoming_offer.transfer_id,
                TransferStatus::Rejected,
                Some(receiver_status),
            )
            .await;
        TransferResponseMessage::rejected(
            incoming_offer.transfer_id.clone(),
            Some(response_message),
        )
    };

    if state
        .clear_incoming_offer(&incoming_offer.transfer_id)
        .await
    {
        events::emit_incoming_transfer(&app, &None)?;
    }
    emit_transfers(&state, &app).await?;
    if let Err(error) = write_message(stream, &ControlMessage::TransferResponse(response)).await {
        if accepted {
            let error =
                stream::prefer_peer_cancel(stream, &incoming_offer.transfer_id, error).await;
            return finish_failed_incoming(&state, &app, &incoming_offer.transfer_id, error, false)
                .await;
        }
        return Err(error);
    }

    if accepted {
        let destination_path = destination_path
            .ok_or_else(|| "missing destination path for an approved transfer".to_string())?;
        let (cancel_flag, pause_flag) = state
            .register_incoming_transfer(incoming_offer.transfer_id.clone())
            .await;

        state
            .set_transfer_status(
                &incoming_offer.transfer_id,
                TransferStatus::Transferring,
                Some(format!("Saving into {}", destination_path)),
            )
            .await;
        emit_transfers(&state, &app).await?;

        let saved_into = match match selected_transfer_encoding {
            TransferEncoding::FramedV2 => {
                stream::receive_transfer_stream_framed_v2(
                    &state,
                    &app,
                    stream,
                    &incoming_offer.transfer_id,
                    &incoming_offer.sender_name,
                    &destination_path,
                    cancel_flag.clone(),
                    pause_flag.clone(),
                )
                .await
            }
            TransferEncoding::RawV1 => {
                stream::receive_transfer_stream(
                    &state,
                    &app,
                    stream,
                    &incoming_offer.transfer_id,
                    &incoming_offer.sender_name,
                    &destination_path,
                    cancel_flag.clone(),
                    pause_flag.clone(),
                )
                .await
            }
        } {
            Ok(path) => path,
            Err(error) => {
                let _ = write_message(
                    stream,
                    &ControlMessage::TransferReceipt(TransferReceiptMessage::failure(
                        incoming_offer.transfer_id.clone(),
                        Some(error.clone()),
                    )),
                )
                .await;
                return finish_failed_incoming(
                    &state,
                    &app,
                    &incoming_offer.transfer_id,
                    error,
                    cancel_flag.load(Ordering::SeqCst),
                )
                .await;
            }
        };

        let receipt = TransferReceiptMessage::success(
            incoming_offer.transfer_id.clone(),
            Some("Receiver committed the transferred files to disk.".to_string()),
            Some(saved_into.clone()),
        );
        if cancel_flag.load(Ordering::SeqCst) {
            return finish_failed_incoming(
                &state,
                &app,
                &incoming_offer.transfer_id,
                CANCELED_MESSAGE.to_string(),
                true,
            )
            .await;
        }
        if let Err(error) = write_message(stream, &ControlMessage::TransferReceipt(receipt)).await {
            let error =
                stream::prefer_peer_cancel(stream, &incoming_offer.transfer_id, error).await;
            return finish_failed_incoming(
                &state,
                &app,
                &incoming_offer.transfer_id,
                error,
                cancel_flag.load(Ordering::SeqCst),
            )
            .await;
        }

        state
            .set_transfer_status(
                &incoming_offer.transfer_id,
                TransferStatus::Completed,
                Some(format!("Transfer completed in {}", saved_into)),
            )
            .await;
        state.finish_transfer(&incoming_offer.transfer_id).await;
        emit_transfers(&state, &app).await?;
        let _ = notifications::notify_info(
            &app,
            "Files received",
            &format!("Saved incoming files into {}", saved_into),
        );
    }
    Ok(())
}

async fn handle_peer_hello_request(state: AppState, stream: &mut TcpStream) -> Result<(), String> {
    let identity = state.discovery_identity().await;
    let response = PeerHelloResponseMessage::new(
        identity.device_id,
        identity.instance_id,
        identity.device_name,
        identity.device_kind,
        identity.tcp_port,
        identity.availability,
        identity.requires_pair_code,
    );
    write_message(stream, &ControlMessage::PeerHelloResponse(response)).await
}

async fn emit_transfers(state: &AppState, app: &tauri::AppHandle) -> Result<(), String> {
    let transfers = state.transfers().await;
    events::emit_transfers_updated(app, &transfers)
}

async fn finish_failed_incoming(
    state: &AppState,
    app: &tauri::AppHandle,
    transfer_id: &str,
    error: String,
    locally_canceled: bool,
) -> Result<(), String> {
    let error = transfer_error_message(error, locally_canceled);
    let status = if is_cancellation_error(&error) {
        TransferStatus::Canceled
    } else {
        TransferStatus::Failed
    };
    state
        .set_transfer_status(transfer_id, status, Some(error.clone()))
        .await;
    let _ = state.cancel_transfer(transfer_id).await;
    state.finish_transfer(transfer_id).await;
    emit_transfers(state, app).await?;
    Err(error)
}

async fn wait_incoming_decision(
    stream: &mut TcpStream,
    transfer_id: &str,
    decision: tokio::sync::oneshot::Receiver<IncomingTransferDecision>,
) -> Result<IncomingTransferDecision, String> {
    let mut byte = [0u8; 1];
    tokio::select! {
        biased;
        // Peeking can safely be abandoned if the UI answers. Once a byte is
        // available, consume the whole frame before accepting any UI response.
        readable = stream.peek(&mut byte) => {
            readable.map_err(|error| error.to_string())?;
            let message = timeout(CONNECT_TIMEOUT, read_message(stream)).await
                .map_err(|_| "Sender did not finish the cancellation message.".to_string())??;
            match message {
                ControlMessage::TransferFlowControl(flow)
                    if flow.transfer_id == transfer_id
                        && matches!(flow.state, FlowControlState::CancelRequested) =>
                    Err(PEER_CANCELED.to_string()),
                _ => Err("Unexpected sender message before acceptance.".to_string()),
            }
        }
        decision = decision => decision.map_err(|_| "Incoming approval flow closed before a response was received.".to_string()),
    }
}

fn select_transfer_encoding(supported: Option<&[TransferEncoding]>) -> TransferEncoding {
    let Some(supported) = supported else {
        return TransferEncoding::RawV1;
    };

    if supported.contains(&TransferEncoding::FramedV2) {
        TransferEncoding::FramedV2
    } else {
        TransferEncoding::RawV1
    }
}

#[cfg(test)]
mod approval_tests {
    use super::*;
    use crate::transfer::protocol::{FlowControlReason, TransferFlowControlMessage};
    use tokio::io::AsyncWriteExt;

    async fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let sender = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (receiver, _) = listener.accept().await.unwrap();
        (sender, receiver)
    }

    fn cancel_message() -> ControlMessage {
        ControlMessage::TransferFlowControl(TransferFlowControlMessage::new(
            "pending".into(),
            FlowControlState::CancelRequested,
            FlowControlReason::User,
        ))
    }

    #[tokio::test]
    async fn sender_cancel_withdraws_offer_without_a_receiver_response() {
        let (mut sender, mut receiver) = connected_pair().await;
        let (_decision_tx, decision_rx) = tokio::sync::oneshot::channel();
        write_message(&mut sender, &cancel_message()).await.unwrap();
        assert_eq!(
            timeout(
                Duration::from_secs(1),
                wait_incoming_decision(&mut receiver, "pending", decision_rx)
            )
            .await
            .unwrap()
            .unwrap_err(),
            PEER_CANCELED
        );
    }

    #[tokio::test]
    async fn fragmented_cancel_is_not_lost_when_accept_arrives_mid_frame() {
        let (mut sender, mut receiver) = connected_pair().await;
        let (decision_tx, decision_rx) = tokio::sync::oneshot::channel();
        let payload = serde_json::to_vec(&cancel_message()).unwrap();
        let mut frame = (payload.len() as u32).to_be_bytes().to_vec();
        frame.extend(payload);
        sender.write_all(&frame[..2]).await.unwrap();
        let task = tokio::spawn(async move {
            wait_incoming_decision(&mut receiver, "pending", decision_rx).await
        });
        tokio::task::yield_now().await;
        decision_tx
            .send(IncomingTransferDecision {
                accept: true,
                save_path: Some("unused".into()),
            })
            .unwrap();
        sender.write_all(&frame[2..]).await.unwrap();
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
    async fn disconnected_sender_releases_pending_approval() {
        let (sender, mut receiver) = connected_pair().await;
        let (_decision_tx, decision_rx) = tokio::sync::oneshot::channel();
        drop(sender);
        let error = timeout(
            Duration::from_secs(1),
            wait_incoming_decision(&mut receiver, "pending", decision_rx),
        )
        .await
        .unwrap()
        .unwrap_err();
        assert_eq!(
            transfer_error_message(error, false),
            crate::transfer::error::PEER_DISCONNECTED
        );
    }

    #[tokio::test]
    async fn receiver_can_accept_a_live_pending_offer() {
        let (_sender, mut receiver) = connected_pair().await;
        let (decision_tx, decision_rx) = tokio::sync::oneshot::channel();
        decision_tx
            .send(IncomingTransferDecision {
                accept: true,
                save_path: Some("chosen-folder".into()),
            })
            .unwrap();
        let result = timeout(
            Duration::from_secs(1),
            wait_incoming_decision(&mut receiver, "pending", decision_rx),
        )
        .await
        .unwrap()
        .unwrap();
        assert!(result.accept);
        assert_eq!(result.save_path.as_deref(), Some("chosen-folder"));
    }

    #[tokio::test]
    async fn cancel_while_receiver_is_silent_does_not_wait_five_minutes() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let sender = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (_receiver, _) = listener.accept().await.unwrap();
        let cancel = Arc::new(AtomicBool::new(false));
        let task_cancel = cancel.clone();
        let task = tokio::spawn(async move {
            let mut sender = sender;
            read_offer_response(&mut sender, &task_cancel).await
        });
        tokio::task::yield_now().await;
        cancel.store(true, Ordering::SeqCst);
        let error = timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap_err();
        assert_eq!(error, CANCELED_MESSAGE);
    }

    #[tokio::test]
    async fn rejected_offer_returns_the_receiver_reason_for_immediate_retry() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut sender = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut receiver, _) = listener.accept().await.unwrap();
        write_message(
            &mut receiver,
            &ControlMessage::TransferResponse(TransferResponseMessage::rejected(
                "test".to_string(),
                Some("Choose a save folder.".to_string()),
            )),
        )
        .await
        .unwrap();
        let response = read_offer_response(&mut sender, &AtomicBool::new(false))
            .await
            .unwrap();
        match response {
            ControlMessage::TransferResponse(response) => {
                assert!(!response.accepted);
                assert_eq!(response.message.as_deref(), Some("Choose a save folder."));
            }
            _ => panic!("unexpected response"),
        }
    }
}

pub fn is_loopback_ip(ip: &str) -> bool {
    let trimmed = ip.trim().trim_matches(|c| c == '[' || c == ']');
    if trimmed.eq_ignore_ascii_case("localhost") {
        return true;
    }
    if let Ok(addr) = trimmed.parse::<IpAddr>() {
        return addr.is_loopback();
    }
    // Bare IPv4 loopback without successful parse fallback.
    trimmed.starts_with("127.")
}
