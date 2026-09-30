use std::collections::HashSet;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket as StdUdpSocket};

use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio::time::{self, Duration};
use tracing::{debug, warn};

use crate::app::AppState;
use crate::discovery::adapter::{enumerate_discovery_endpoints, DiscoveryEndpoint};
use crate::discovery::protocol::{DiscoveryPacket, DiscoveryPacketKind};
use crate::events;

const STALE_PEER_AFTER_MS: u64 = 30_000;

#[derive(Debug, Clone, Copy)]
pub enum DiscoveryCommand {
    ProbeNow,
    AnnounceNow,
}

pub async fn run(
    state: AppState,
    app: tauri::AppHandle,
    mut commands: mpsc::UnboundedReceiver<DiscoveryCommand>,
) {
    let socket = match UdpSocket::bind(("0.0.0.0", 50500)).await {
        Ok(socket) => socket,
        Err(error) => {
            warn!("unable to bind UDP discovery socket: {error}");
            return;
        }
    };

    if let Err(error) = socket.set_broadcast(true) {
        warn!("unable to enable UDP broadcast: {error}");
        return;
    }

    let mut announce_tick = time::interval(Duration::from_secs(5));
    let mut prune_tick = time::interval(Duration::from_secs(3));
    let mut buffer = vec![0u8; 64 * 1024];

    loop {
        tokio::select! {
            command = commands.recv() => {
                match command {
                    Some(DiscoveryCommand::ProbeNow) => {
                        let _ = state.refresh_local_adapters().await;
                        if let Err(error) = send_broadcast(&socket, &state, DiscoveryPacketKind::Query).await {
                            warn!("unable to broadcast discovery query: {error}");
                        }
                    }
                    Some(DiscoveryCommand::AnnounceNow) => {
                        let _ = state.refresh_local_adapters().await;
                        if let Err(error) = send_broadcast(&socket, &state, DiscoveryPacketKind::Hello).await {
                            warn!("unable to broadcast discovery hello: {error}");
                        }
                    }
                    None => break,
                }
            }
            _ = announce_tick.tick() => {
                if let Err(error) = send_broadcast(&socket, &state, DiscoveryPacketKind::Hello).await {
                    debug!("periodic hello broadcast skipped: {error}");
                }
            }
            _ = prune_tick.tick() => {
                if state.prune_stale_peers(STALE_PEER_AFTER_MS).await {
                    let peers = state.peers().await;
                    let _ = events::emit_devices_updated(&app, &peers);
                }
            }
            result = socket.recv_from(&mut buffer) => {
                match result {
                    Ok((len, from)) => {
                        if let Err(error) = handle_packet(&socket, &state, &app, &buffer[..len], from).await {
                            debug!("ignoring discovery packet: {error}");
                        }
                    }
                    Err(error) => {
                        warn!("UDP discovery receive failed: {error}");
                    }
                }
            }
        }
    }
}

async fn handle_packet(
    socket: &UdpSocket,
    state: &AppState,
    app: &tauri::AppHandle,
    bytes: &[u8],
    from: SocketAddr,
) -> Result<(), String> {
    let packet =
        serde_json::from_slice::<DiscoveryPacket>(bytes).map_err(|error| error.to_string())?;

    if !packet.is_valid() {
        return Ok(());
    }

    let identity = state.discovery_identity().await;
    if packet.instance_id == identity.instance_id {
        return Ok(());
    }

    match packet.kind {
        DiscoveryPacketKind::Query => {
            upsert_packet_peer(state, app, &packet, from).await?;

            let response = DiscoveryPacket::from_identity(identity, DiscoveryPacketKind::Response);
            let encoded = serde_json::to_vec(&response).map_err(|error| error.to_string())?;
            socket
                .send_to(&encoded, from)
                .await
                .map_err(|error| error.to_string())?;
        }
        DiscoveryPacketKind::Goodbye => {}
        DiscoveryPacketKind::Hello | DiscoveryPacketKind::Response => {
            upsert_packet_peer(state, app, &packet, from).await?;
        }
    }

    Ok(())
}

async fn upsert_packet_peer(
    state: &AppState,
    app: &tauri::AppHandle,
    packet: &DiscoveryPacket,
    from: SocketAddr,
) -> Result<(), String> {
    if let Some(mut peer) = packet.to_peer() {
        if let SocketAddr::V4(source) = from {
            let source_ip = *source.ip();
            if source_ip.is_private() || !is_private_peer_ip(&peer.ip) {
                peer.ip = source_ip.to_string();
            }
        }
        let changed = state.upsert_peer(peer).await;
        if changed {
            let peers = state.peers().await;
            events::emit_devices_updated(app, &peers)?;
        }
    }
    Ok(())
}

fn is_private_peer_ip(ip: &str) -> bool {
    ip.parse::<Ipv4Addr>()
        .map(|address| address.is_private())
        .unwrap_or(false)
}

async fn send_broadcast(
    socket: &UdpSocket,
    state: &AppState,
    kind: DiscoveryPacketKind,
) -> Result<(), String> {
    let identity = state.discovery_identity().await;
    if identity.addresses.is_empty() {
        return Ok(());
    }

    let packet = DiscoveryPacket::from_identity(identity, kind);
    let payload = serde_json::to_vec(&packet).map_err(|error| error.to_string())?;
    let settings = state.bootstrap().await.settings;
    let disabled = settings.disabled_adapter_ids;

    let mut sent_any = false;
    let mut last_error = None;

    for endpoint in dedupe_endpoints(enumerate_discovery_endpoints()) {
        if disabled.iter().any(|id| id == &endpoint.adapter_id) {
            continue;
        }
        if endpoint.bind_ip == Ipv4Addr::UNSPECIFIED {
            let target = SocketAddr::V4(SocketAddrV4::new(endpoint.broadcast_ip, 50500));
            match socket.send_to(&payload, target).await {
                Ok(_) => sent_any = true,
                Err(error) => {
                    last_error = Some(error.to_string());
                    debug!("discovery send to {target} skipped: {error}");
                }
            }
        } else {
            match send_adapter_scoped_packet(&payload, &endpoint) {
                Ok(()) => sent_any = true,
                Err(error) => {
                    last_error = Some(error);
                }
            }
        }
    }

    if sent_any {
        Ok(())
    } else {
        Err(last_error.unwrap_or_else(|| "no discovery packets could be sent".to_string()))
    }
}

fn send_adapter_scoped_packet(payload: &[u8], endpoint: &DiscoveryEndpoint) -> Result<(), String> {
    let socket = StdUdpSocket::bind(SocketAddrV4::new(endpoint.bind_ip, 0))
        .map_err(|error| format!("bind discovery sender {} failed: {error}", endpoint.bind_ip))?;
    socket
        .set_broadcast(true)
        .map_err(|error| format!("enable adapter broadcast failed: {error}"))?;
    let target = SocketAddrV4::new(endpoint.broadcast_ip, 50500);
    socket
        .send_to(payload, target)
        .map_err(|error| format!("adapter discovery send to {target} failed: {error}"))?;
    Ok(())
}

fn dedupe_endpoints(endpoints: Vec<DiscoveryEndpoint>) -> Vec<DiscoveryEndpoint> {
    let mut seen = HashSet::new();
    let mut unique = Vec::new();

    for endpoint in endpoints {
        let key = (endpoint.bind_ip.octets(), endpoint.broadcast_ip.octets());

        if seen.insert(key) {
            unique.push(endpoint);
        }
    }

    if unique.is_empty() {
        unique.push(DiscoveryEndpoint {
            adapter_id: "default".to_string(),
            bind_ip: Ipv4Addr::UNSPECIFIED,
            broadcast_ip: Ipv4Addr::new(255, 255, 255, 255),
            priority: 0,
        });
    }

    unique
}
