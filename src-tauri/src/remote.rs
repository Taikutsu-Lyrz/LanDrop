use std::env;
use std::future::pending;
use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use igd_next::aio::tokio::search_gateway;
use igd_next::PortMappingProtocol;
use rcgen::{generate_simple_self_signed, CertifiedKey};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{ClientConfig, RootCertStore, ServerConfig};
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream, UdpSocket};
use tokio::sync::Semaphore;
use tokio::time::{sleep, timeout, Duration};
use tokio_rustls::{TlsAcceptor, TlsConnector};
use tracing::{debug, warn};
use uuid::Uuid;

use crate::app::AppState;
use crate::remote_codec::{
    certificate_fingerprint, decode_invite, encode_compact_invite, fingerprints_equal,
    is_public_ip, parse_public_endpoint, parse_stun_mapped_address, RemoteInvite,
    REMOTE_PROTOCOL_VERSION, STUN_MAGIC_COOKIE,
};
#[cfg(test)]
use crate::remote_codec::COMPACT_REMOTE_LINK_PREFIX;
use crate::transfer;
use crate::transfer::protocol::{read_message, write_message, ControlMessage, TransferEncoding};
use crate::types::{LocalAdapterStatus, PeerDevice, RemoteAccessState, RemoteAccessStatus};

const REMOTE_SERVER_NAME: &str = "landrop.remote";
const MAX_REMOTE_HANDSHAKE: usize = 8 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const PORT_MAPPING_LEASE_SECONDS: u32 = 3600;
const PORT_MAPPING_RENEW_AFTER: Duration = Duration::from_secs(30 * 60);
const MAX_UNAUTHENTICATED_SESSIONS: usize = 8;
const STUN_SERVERS: &[(&str, u16)] = &[("stun.cloudflare.com", 3478), ("stun.l.google.com", 19302)];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteIdentity {
    certificate_der: Vec<u8>,
    private_key_der: Vec<u8>,
    access_token: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteAuthRequest {
    version: u16,
    access_token: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteAuthResponse {
    accepted: bool,
    message: Option<String>,
}

pub async fn ensure_listener(state: AppState, app: tauri::AppHandle) -> Result<(), String> {
    let settings = state.bootstrap().await.settings;
    if !settings.remote_access_enabled {
        return Ok(());
    }
    if !state.try_mark_remote_server_running() {
        return Ok(());
    }

    let identity = match tokio::task::spawn_blocking(load_or_create_identity).await {
        Ok(Ok(identity)) => identity,
        Ok(Err(error)) => {
            state.mark_remote_server_stopped();
            return Err(error);
        }
        Err(error) => {
            state.mark_remote_server_stopped();
            return Err(error.to_string());
        }
    };
    let server_config = match build_server_config(&identity) {
        Ok(config) => Arc::new(config),
        Err(error) => {
            state.mark_remote_server_stopped();
            return Err(error);
        }
    };
    let (ipv4_listener, ipv6_listener) = match bind_remote_listeners(settings.remote_listen_port).await {
        Ok(listeners) => listeners,
        Err(error) => {
            state.mark_remote_server_stopped();
            return Err(format!(
                "Unable to bind secure remote port {}: {error}",
                settings.remote_listen_port
            ));
        }
    };

    let port = settings.remote_listen_port;
    let local_transfer_port = settings.tcp_port;
    state
        .set_remote_access_status(RemoteAccessStatus {
            state: RemoteAccessState::Listening,
            message: format!("Secure listener is active on TCP {port}. Checking router mapping."),
            public_host: None,
            external_port: Some(port),
            mapping_method: None,
        })
        .await;
    if settings.remote_auto_port_mapping {
        let status = configure_upnp_mapping(&state, port).await;
        state.set_remote_access_status(status).await;
    } else {
        let status = manual_mapping_status_with_public_ip(port, None).await;
        state.set_remote_access_status(status).await;
    }

    tauri::async_runtime::spawn(async move {
        let acceptor = TlsAcceptor::from(server_config);
        let connection_slots = Arc::new(Semaphore::new(MAX_UNAUTHENTICATED_SESSIONS));
        let mut mapping_refreshed_at = Instant::now();
        loop {
            if !state.bootstrap().await.settings.remote_access_enabled {
                break;
            }

            tokio::select! {
                accepted = ipv4_listener.accept() =>
                    spawn_remote_session(accepted, &acceptor, &identity, &connection_slots, local_transfer_port),
                accepted = async {
                    match ipv6_listener.as_ref() {
                        Some(listener) => listener.accept().await,
                        None => pending::<std::io::Result<(TcpStream, SocketAddr)>>().await,
                    }
                } => spawn_remote_session(accepted, &acceptor, &identity, &connection_slots, local_transfer_port),
                _ = sleep(Duration::from_millis(500)) => {
                    let current_settings = state.bootstrap().await.settings;
                    if current_settings.remote_auto_port_mapping
                        && mapping_refreshed_at.elapsed() >= PORT_MAPPING_RENEW_AFTER
                    {
                        let status =
                            configure_upnp_mapping(&state, current_settings.remote_listen_port).await;
                        state.set_remote_access_status(status).await;
                        mapping_refreshed_at = Instant::now();
                    }
                }
            }
        }
        if settings.remote_auto_port_mapping {
            remove_upnp_mapping(port).await;
        }
        state.mark_remote_server_stopped();
        state
            .set_remote_access_status(RemoteAccessStatus::disabled())
            .await;
        let _ = crate::notifications::notify_info(
            &app,
            "Remote access stopped",
            &format!("Secure remote port {port} is closed."),
        );
    });

    Ok(())
}

fn spawn_remote_session(
    accepted: std::io::Result<(TcpStream, SocketAddr)>,
    acceptor: &TlsAcceptor,
    identity: &RemoteIdentity,
    connection_slots: &Arc<Semaphore>,
    local_transfer_port: u16,
) {
    match accepted {
        Ok((stream, from)) => {
            let Ok(connection_slot) = connection_slots.clone().try_acquire_owned() else {
                debug!("remote connection from {from} dropped: listener is busy");
                return;
            };
            let acceptor = acceptor.clone();
            let identity = identity.clone();
            tauri::async_runtime::spawn(async move {
                let _connection_slot = connection_slot;
                if let Err(error) = handle_remote_connection(
                    acceptor,
                    identity,
                    stream,
                    local_transfer_port,
                    Some(_connection_slot),
                )
                .await
                {
                    debug!("remote connection from {from} ended: {error}");
                }
            });
        }
        Err(error) => warn!("remote listener accept failed: {error}"),
    }
}

pub async fn create_invite(state: AppState, public_host: String) -> Result<String, String> {
    let settings = state.bootstrap().await.settings;
    if !settings.remote_access_enabled {
        return Err("Enable Remote access before creating an invite.".to_string());
    }
    let access_status = state.remote_access_status().await;
    let endpoint = if public_host.trim().is_empty() {
        match access_status.state {
            RemoteAccessState::Mapped => access_status
                .public_host
                .ok_or_else(|| {
                    "Router mapping looks ready, but no public endpoint is available yet. Try Check router again."
                        .to_string()
                })?,
            _ => {
                return Err(
                    "LanDrop has not verified that TCP 50502 is reachable yet. Enter the forwarded public IP, IPv6 address, or DNS name manually before creating an invite."
                        .to_string(),
                )
            }
        }
    } else {
        public_host.trim().to_string()
    };
    let (host, remote_port) = parse_public_endpoint(&endpoint, settings.remote_listen_port)?;
    create_invite_for_endpoint(settings, host, remote_port).await
}

/// Creates an invite for a private overlay network such as Tailscale, Headscale, or WireGuard.
/// The caller must supply an address reachable by both devices; no public routing check or router
/// port mapping is required because the overlay transports the connection.
pub async fn create_overlay_invite(state: AppState, overlay_endpoint: String) -> Result<String, String> {
    let settings = state.bootstrap().await.settings;
    if !settings.remote_access_enabled {
        return Err("Enable Remote access before creating an invite.".to_string());
    }
    let (host, remote_port) = parse_public_endpoint(&overlay_endpoint, settings.remote_listen_port)
        .map_err(|_| {
            "Enter this PC's VPN/overlay address or hostname, optionally followed by :port."
                .to_string()
        })?;
    create_invite_for_endpoint(settings, host, remote_port).await
}

async fn create_invite_for_endpoint(
    settings: crate::config::settings::AppSettings,
    host: String,
    remote_port: u16,
) -> Result<String, String> {
    let identity = tokio::task::spawn_blocking(load_or_create_identity)
        .await
        .map_err(|error| error.to_string())??;
    let invite = RemoteInvite {
        version: REMOTE_PROTOCOL_VERSION,
        device_name: settings.device_name,
        host,
        port: remote_port,
        certificate_der: URL_SAFE_NO_PAD.encode(&identity.certificate_der),
        certificate_fingerprint: certificate_fingerprint(&identity.certificate_der),
        access_token: identity.access_token,
    };
    encode_compact_invite(&invite, &identity.certificate_der)
}

pub async fn refresh_mapping(state: AppState) -> RemoteAccessStatus {
    let settings = state.bootstrap().await.settings;
    if !settings.remote_access_enabled {
        let status = RemoteAccessStatus::disabled();
        state.set_remote_access_status(status.clone()).await;
        return status;
    }
    let status = if settings.remote_auto_port_mapping {
        configure_upnp_mapping(&state, settings.remote_listen_port).await
    } else {
        manual_mapping_status_with_public_ip(settings.remote_listen_port, None).await
    };
    state.set_remote_access_status(status.clone()).await;
    status
}

pub async fn reset_identity(state: AppState) -> Result<(), String> {
    if state.bootstrap().await.settings.remote_access_enabled {
        return Err("Disable Remote access before resetting its identity.".to_string());
    }
    tokio::task::spawn_blocking(|| {
        let path = remote_identity_path();
        if path.exists() {
            fs::remove_file(path).map_err(|error| error.to_string())?;
        }
        Ok::<(), String>(())
    })
    .await
    .map_err(|error| error.to_string())??;
    state
        .set_remote_access_status(RemoteAccessStatus::disabled())
        .await;
    Ok(())
}

pub async fn connect_invite(
    state: AppState,
    app: tauri::AppHandle,
    encoded_invite: String,
) -> Result<PeerDevice, String> {
    let invite = decode_invite(&encoded_invite)?;
    let certificate_der = URL_SAFE_NO_PAD
        .decode(invite.certificate_der.as_bytes())
        .map_err(|_| "Remote invite contains an invalid certificate.".to_string())?;
    if !fingerprints_equal(
        &certificate_fingerprint(&certificate_der),
        &invite.certificate_fingerprint,
    ) {
        return Err("Remote invite certificate fingerprint does not match.".to_string());
    }
    let connector = build_client_connector(certificate_der)?;
    let local_listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|error| error.to_string())?;
    let local_port = local_listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();

    let tunnel_invite = invite.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            let (local_stream, _) = match local_listener.accept().await {
                Ok(value) => value,
                Err(error) => {
                    warn!("remote local tunnel stopped: {error}");
                    break;
                }
            };
            let connector = connector.clone();
            let invite = tunnel_invite.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(error) = tunnel_connection(local_stream, connector, invite).await {
                    debug!("remote tunnel connection ended: {error}");
                }
            });
        }
    });

    let peer = transfer::control::probe_manual_peer(
        state.clone(),
        app.clone(),
        "127.0.0.1".to_string(),
        Some(local_port),
    )
    .await?;
    // The probed peer lives behind the local tunnel (127.0.0.1), which must
    // not become the trusted address. Trust the overlay endpoint the user
    // actually connected through instead: Tailscale 100.x, MagicDNS name, or
    // any other VPN/hostname from the invite. probe_manual_peer skips
    // loopback trust, so record the real endpoint here.
    let overlay_host = invite.host.trim().to_string();
    if !overlay_host.is_empty() && !transfer::control::is_loopback_ip(&overlay_host) {
        state
            .trust_device(&peer.device_id, &peer.name, &overlay_host)
            .await;
        let _ = crate::events::emit_settings_updated(&app, &state.bootstrap().await.settings);
    }
    Ok(peer)
}

async fn handle_remote_connection(
    acceptor: TlsAcceptor,
    identity: RemoteIdentity,
    stream: TcpStream,
    local_transfer_port: u16,
    unauthenticated_slot: Option<tokio::sync::OwnedSemaphorePermit>,
) -> Result<(), String> {
    let mut tls_stream = timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream))
        .await
        .map_err(|_| "Remote TLS handshake timed out.".to_string())?
        .map_err(|error| format!("Remote TLS handshake failed: {error}"))?;
    let auth: RemoteAuthRequest = timeout(HANDSHAKE_TIMEOUT, read_remote_message(&mut tls_stream))
        .await
        .map_err(|_| "Remote authorization timed out.".to_string())??;
    let token_matches: bool = auth
        .access_token
        .as_bytes()
        .ct_eq(identity.access_token.as_bytes())
        .into();
    if auth.version != REMOTE_PROTOCOL_VERSION || !token_matches {
        let _ = write_remote_message(
            &mut tls_stream,
            &RemoteAuthResponse {
                accepted: false,
                message: Some("Remote invitation is invalid or revoked.".to_string()),
            },
        )
        .await;
        return Err("Remote authorization failed.".to_string());
    }

    // Authenticated sessions no longer consume the unauthenticated connection budget.
    drop(unauthenticated_slot);

    write_remote_message(
        &mut tls_stream,
        &RemoteAuthResponse {
            accepted: true,
            message: None,
        },
    )
    .await?;
    let first_message = match timeout(HANDSHAKE_TIMEOUT, read_message(&mut tls_stream)).await {
        Err(_) => return Err("Remote protocol negotiation timed out.".to_string()),
        Ok(Err(error)) => {
            let message = error.to_string();
            if message.contains("early eof")
                || message.contains("UnexpectedEof")
                || message.contains("close_notify")
                || message.contains("connection reset")
                || message.contains("Broken pipe")
                || message.contains("failed to fill whole buffer")
            {
                return Ok(());
            }
            return Err(message);
        }
        Ok(Ok(message)) => message,
    };
    validate_remote_first_message(&first_message)?;

    let mut local_stream = match TcpStream::connect(("127.0.0.1", local_transfer_port)).await {
        Ok(stream) => stream,
        Err(error) => {
            return Err(format!("Local transfer listener is unavailable: {error}"));
        }
    };
    write_message(&mut local_stream, &first_message).await?;
    match copy_bidirectional(&mut tls_stream, &mut local_stream).await {
        Ok(_) => Ok(()),
        Err(error) => {
            let message = error.to_string();
            // Peers may close after a completed transfer without TLS close_notify.
            if message.contains("close_notify") || message.contains("UnexpectedEof") {
                Ok(())
            } else {
                Err(message)
            }
        }
    }
}

fn validate_remote_first_message(message: &ControlMessage) -> Result<(), String> {
    match message {
        ControlMessage::PeerHelloRequest(_) | ControlMessage::ParallelWorkerOpen(_) => Ok(()),
        ControlMessage::TransferOffer(offer)
            if offer
                .supported_transfer_encodings
                .as_ref()
                .is_some_and(|encodings| encodings.contains(&TransferEncoding::FramedV2)) =>
        {
            Ok(())
        }
        ControlMessage::TransferOffer(_) => {
            Err("Remote transfers require the framed v2 protocol.".to_string())
        }
        _ => Err("Unexpected first frame on the remote listener.".to_string()),
    }
}

async fn tunnel_connection(
    mut local_stream: TcpStream,
    connector: TlsConnector,
    invite: RemoteInvite,
) -> Result<(), String> {
    let remote_stream = timeout(
        CONNECT_TIMEOUT,
        TcpStream::connect((invite.host.as_str(), invite.port)),
    )
    .await
    .map_err(|_| "Remote computer connection timed out.".to_string())?
    .map_err(|error| format!("Remote computer is unreachable: {error}"))?;
    let server_name =
        ServerName::try_from(REMOTE_SERVER_NAME.to_string()).map_err(|error| error.to_string())?;
    let mut tls_stream = timeout(
        HANDSHAKE_TIMEOUT,
        connector.connect(server_name, remote_stream),
    )
    .await
    .map_err(|_| "Remote TLS handshake timed out.".to_string())?
    .map_err(|error| format!("Remote identity verification failed: {error}"))?;
    write_remote_message(
        &mut tls_stream,
        &RemoteAuthRequest {
            version: REMOTE_PROTOCOL_VERSION,
            access_token: invite.access_token,
        },
    )
    .await?;
    let response: RemoteAuthResponse =
        timeout(HANDSHAKE_TIMEOUT, read_remote_message(&mut tls_stream))
            .await
            .map_err(|_| "Remote authorization timed out.".to_string())??;
    if !response.accepted {
        return Err(response
            .message
            .unwrap_or_else(|| "Remote invitation was rejected.".to_string()));
    }
    match copy_bidirectional(&mut local_stream, &mut tls_stream).await {
        Ok(_) => Ok(()),
        Err(error) => {
            let message = error.to_string();
            if message.contains("close_notify") || message.contains("UnexpectedEof") {
                Ok(())
            } else {
                Err(message)
            }
        }
    }
}

fn build_server_config(identity: &RemoteIdentity) -> Result<ServerConfig, String> {
    let certificate = CertificateDer::from(identity.certificate_der.clone());
    let private_key =
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key_der.clone()));
    let mut config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![certificate], private_key)
        .map_err(|error| error.to_string())?;
    config.alpn_protocols = vec![b"landrop/2".to_vec()];
    Ok(config)
}

fn build_client_connector(certificate_der: Vec<u8>) -> Result<TlsConnector, String> {
    let mut roots = RootCertStore::empty();
    roots
        .add(CertificateDer::from(certificate_der))
        .map_err(|error| error.to_string())?;
    let mut config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"landrop/2".to_vec()];
    Ok(TlsConnector::from(Arc::new(config)))
}

async fn configure_upnp_mapping(state: &AppState, port: u16) -> RemoteAccessStatus {
    let bootstrap = state.bootstrap().await;
    let local_addr = match select_mapping_address(&bootstrap.local_adapters, port) {
        Some(address) => address,
        None => {
            return manual_mapping_status_with_public_ip(
                port,
                Some("No active router-based IPv4 adapter is available.".to_string()),
            )
            .await
        }
    };
    let gateway = match search_gateway(Default::default()).await {
        Ok(gateway) => gateway,
        Err(error) => {
            return manual_mapping_status_with_public_ip(
                port,
                Some(format!("Automatic router discovery failed: {error}")),
            )
            .await
        }
    };
    let external_ip = match gateway.get_external_ip().await {
        Ok(ip) => ip,
        Err(error) => {
            return manual_mapping_status_with_public_ip(
                port,
                Some(format!(
                    "Router did not report an external address: {error}"
                )),
            )
            .await
        }
    };
    if let Err(error) = gateway
        .add_port(
            PortMappingProtocol::TCP,
            port,
            local_addr,
            PORT_MAPPING_LEASE_SECONDS,
            "LanDrop secure remote transfer",
        )
        .await
    {
        return manual_mapping_status_with_public_ip(
            port,
            Some(format!("Router rejected automatic port mapping: {error}")),
        )
        .await;
    }

    if !is_public_ip(external_ip) {
        let detected_public_ip = discover_public_ip().await;
        return RemoteAccessStatus {
            state: RemoteAccessState::ManualRequired,
            message: match detected_public_ip {
                Some(public_ip) => format!(
                    "This PC is behind double NAT. The home router mapped TCP {port}, but its WAN address {external_ip} is private, so phones cannot reach {public_ip}:{port} yet. Forward TCP {port} on the outer modem/router to {external_ip}, or put this router in bridge/DMZ mode. Do not create a QR invite until that outer hop is fixed."
                ),
                None => format!(
                    "This PC is behind double NAT. The home router mapped TCP {port}, but its WAN address {external_ip} is private. Forward TCP {port} on the outer modem/router to {external_ip}, or put this router in bridge/DMZ mode before creating a QR invite."
                ),
            },
            // STUN can see an internet IP, but inbound TCP through the outer NAT is not verified.
            public_host: None,
            external_port: Some(port),
            mapping_method: Some("upnp-double-nat".to_string()),
        };
    }

    RemoteAccessStatus {
        state: RemoteAccessState::Mapped,
        message: format!("Router mapped {external_ip}:{port} with UPnP."),
        public_host: Some(external_ip.to_string()),
        external_port: Some(port),
        mapping_method: Some("upnp".to_string()),
    }
}

async fn remove_upnp_mapping(port: u16) {
    if let Ok(gateway) = search_gateway(Default::default()).await {
        let _ = gateway.remove_port(PortMappingProtocol::TCP, port).await;
    }
}

fn select_mapping_address(
    adapters: &[crate::types::LocalAdapter],
    port: u16,
) -> Option<SocketAddr> {
    adapters
        .iter()
        .filter(|adapter| adapter.status == LocalAdapterStatus::Active)
        .filter(|adapter| !adapter.gateway_addresses.is_empty())
        .flat_map(|adapter| adapter.ipv4_addresses.iter())
        .filter_map(|value| value.parse::<Ipv4Addr>().ok())
        .find(|ip| ip.is_private() && !ip.is_link_local() && !ip.is_loopback())
        .map(|ip| SocketAddr::new(IpAddr::V4(ip), port))
}

async fn manual_mapping_status_with_public_ip(
    port: u16,
    detail: Option<String>,
) -> RemoteAccessStatus {
    let public_ip = discover_public_ip().await;
    let base_message = detail
        .unwrap_or_else(|| format!("Automatic router mapping is unavailable for TCP {port}."));
    RemoteAccessStatus {
        state: RemoteAccessState::ManualRequired,
        message: match public_ip {
            Some(ip) => format!(
                "{base_message} STUN saw internet address {ip}, but inbound TCP {port} is not verified reachable from phones. Forward TCP {port} all the way to this PC (or enable inbound port forwarding in the VPN), then enter that address manually before creating a QR invite."
            ),
            None => format!(
                "{base_message} Forward TCP {port} on the router to this PC, then enter its public address before creating a QR invite."
            ),
        },
        // Do not advertise an unverified STUN IP as the invite endpoint.
        public_host: None,
        external_port: Some(port),
        mapping_method: public_ip.map(|_| "stun-unverified".to_string()),
    }
}

async fn discover_public_ip() -> Option<IpAddr> {
    for (host, port) in STUN_SERVERS {
        let Ok(addresses) = lookup_host((*host, *port)).await else {
            continue;
        };
        for server in addresses {
            let bind_address = if server.is_ipv4() {
                "0.0.0.0:0"
            } else {
                "[::]:0"
            };
            let Ok(socket) = UdpSocket::bind(bind_address).await else {
                continue;
            };
            let transaction_id = *Uuid::new_v4().as_bytes();
            let mut request = [0u8; 20];
            request[0..2].copy_from_slice(&0x0001u16.to_be_bytes());
            request[4..8].copy_from_slice(&STUN_MAGIC_COOKIE.to_be_bytes());
            request[8..20].copy_from_slice(&transaction_id[..12]);
            if timeout(Duration::from_secs(3), socket.send_to(&request, server))
                .await
                .ok()
                .and_then(Result::ok)
                .is_none()
            {
                continue;
            }
            let mut response = [0u8; 1024];
            let Ok(Ok((length, _))) =
                timeout(Duration::from_secs(3), socket.recv_from(&mut response)).await
            else {
                continue;
            };
            if let Some(ip) = parse_stun_mapped_address(&response[..length], &transaction_id[..12])
            {
                if is_public_ip(ip) {
                    return Some(ip);
                }
            }
        }
    }
    None
}


async fn bind_remote_listeners(port: u16) -> Result<(TcpListener, Option<TcpListener>), String> {
    // Always bind IPv4 explicitly: Windows may make an IPv6 wildcard listener IPv6-only,
    // which otherwise rejects Tailscale's IPv4 100.x overlay addresses.
    let ipv4_listener = TcpListener::bind(("0.0.0.0", port))
        .await
        .map_err(|error| format!("IPv4 bind failed: {error}"))?;

    // IPv6 is optional. Some systems use a dual-stack IPv6 socket that conflicts with the
    // explicit IPv4 listener; IPv4 remote sharing remains available in that case.
    let ipv6_listener = match TcpListener::bind(("::", port)).await {
        Ok(listener) => Some(listener),
        Err(error) => {
            debug!("remote IPv6 listener unavailable; IPv4 remains active: {error}");
            None
        }
    };
    Ok((ipv4_listener, ipv6_listener))
}

async fn write_remote_message<W, T>(stream: &mut W, message: &T) -> Result<(), String>
where
    W: AsyncWrite + Unpin + ?Sized,
    T: Serialize,
{
    let payload = serde_json::to_vec(message).map_err(|error| error.to_string())?;
    if payload.len() > MAX_REMOTE_HANDSHAKE {
        return Err("Remote handshake is too large.".to_string());
    }
    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_all(&payload)
        .await
        .map_err(|error| error.to_string())?;
    stream.flush().await.map_err(|error| error.to_string())
}

async fn read_remote_message<R, T>(stream: &mut R) -> Result<T, String>
where
    R: AsyncRead + Unpin + ?Sized,
    T: for<'de> Deserialize<'de>,
{
    let mut length = [0u8; 4];
    stream
        .read_exact(&mut length)
        .await
        .map_err(|error| error.to_string())?;
    let length = u32::from_be_bytes(length) as usize;
    if length > MAX_REMOTE_HANDSHAKE {
        return Err("Remote handshake is too large.".to_string());
    }
    let mut payload = vec![0u8; length];
    stream
        .read_exact(&mut payload)
        .await
        .map_err(|error| error.to_string())?;
    serde_json::from_slice(&payload).map_err(|error| error.to_string())
}

fn load_or_create_identity() -> Result<RemoteIdentity, String> {
    let path = remote_identity_path();
    if path.exists() {
        let protected = fs::read(&path).map_err(|error| error.to_string())?;
        let plaintext = unprotect_for_current_user(&protected)?;
        return serde_json::from_slice(&plaintext).map_err(|error| error.to_string());
    }

    let CertifiedKey { cert, key_pair } =
        generate_simple_self_signed(vec![REMOTE_SERVER_NAME.to_string()])
            .map_err(|error| error.to_string())?;
    let identity = RemoteIdentity {
        certificate_der: cert.der().to_vec(),
        private_key_der: key_pair.serialize_der(),
        access_token: format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple()),
    };
    let plaintext = serde_json::to_vec(&identity).map_err(|error| error.to_string())?;
    let protected = protect_for_current_user(&plaintext)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    fs::write(path, protected).map_err(|error| error.to_string())?;
    Ok(identity)
}

fn remote_identity_path() -> PathBuf {
    env::var("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("LanDrop")
        .join("remote-identity.bin")
}

#[cfg(windows)]
fn protect_for_current_user(data: &[u8]) -> Result<Vec<u8>, String> {
    use std::ptr::null;
    use std::slice;
    use windows_sys::Win32::Foundation::{GetLastError, LocalFree};
    use windows_sys::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    let result = unsafe {
        CryptProtectData(
            &input,
            null(),
            null(),
            null(),
            null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if result == 0 {
        return Err(format!("DPAPI protection failed with error {}", unsafe {
            GetLastError()
        }));
    }
    let protected =
        unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe {
        LocalFree(output.pbData.cast());
    }
    Ok(protected)
}

#[cfg(windows)]
fn unprotect_for_current_user(data: &[u8]) -> Result<Vec<u8>, String> {
    use std::ptr::{null, null_mut};
    use std::slice;
    use windows_sys::Win32::Foundation::{GetLastError, LocalFree};
    use windows_sys::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    let result = unsafe {
        CryptUnprotectData(
            &input,
            null_mut(),
            null(),
            null(),
            null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if result == 0 {
        return Err(format!("DPAPI unprotection failed with error {}", unsafe {
            GetLastError()
        }));
    }
    let plaintext =
        unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe {
        LocalFree(output.pbData.cast());
    }
    Ok(plaintext)
}

#[cfg(not(windows))]
fn protect_for_current_user(_data: &[u8]) -> Result<Vec<u8>, String> {
    Err("Secure remote identity storage is not implemented on this platform.".to_string())
}

#[cfg(not(windows))]
fn unprotect_for_current_user(_data: &[u8]) -> Result<Vec<u8>, String> {
    Err("Secure remote identity storage is not implemented on this platform.".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transfer::protocol::{PeerHelloRequestMessage, PeerHelloResponseMessage};
    use crate::types::Availability;

    fn test_identity() -> RemoteIdentity {
        let CertifiedKey { cert, key_pair } =
            generate_simple_self_signed(vec![REMOTE_SERVER_NAME.to_string()])
                .expect("test certificate should generate");
        RemoteIdentity {
            certificate_der: cert.der().to_vec(),
            private_key_der: key_pair.serialize_der(),
            access_token: "test-access-token-that-is-long-enough".to_string(),
        }
    }

    #[test]
    fn compact_invite_round_trip_preserves_pinned_identity() {
        let identity = test_identity();
        let invite = RemoteInvite {
            version: REMOTE_PROTOCOL_VERSION,
            device_name: "Home PC".to_string(),
            host: "203.0.113.25".to_string(),
            port: 50502,
            certificate_der: URL_SAFE_NO_PAD.encode(&identity.certificate_der),
            certificate_fingerprint: certificate_fingerprint(&identity.certificate_der),
            access_token: identity.access_token.clone(),
        };
        let encoded = encode_compact_invite(&invite, &identity.certificate_der)
            .expect("invite should encode");
        assert!(encoded.starts_with(COMPACT_REMOTE_LINK_PREFIX));
        let decoded = decode_invite(&encoded).expect("invite should decode");
        assert_eq!(decoded.device_name, invite.device_name);
        assert_eq!(decoded.host, invite.host);
        assert_eq!(decoded.port, invite.port);
        assert_eq!(decoded.certificate_der, invite.certificate_der);
        assert_eq!(
            decoded.certificate_fingerprint,
            invite.certificate_fingerprint
        );
        assert_eq!(decoded.access_token, invite.access_token);
    }

    #[test]
    fn overlay_endpoint_accepts_tailscale_and_hostname_addresses() {
        assert_eq!(
            parse_public_endpoint("100.64.20.10", 50502).unwrap(),
            ("100.64.20.10".to_string(), 50502)
        );
        assert_eq!(
            parse_public_endpoint("home-pc.example.ts.net:42817", 50502).unwrap(),
            ("home-pc.example.ts.net".to_string(), 42817)
        );
    }

    #[test]
    fn stun_parser_decodes_xor_mapped_ipv4() {
        let transaction_id = [7u8; 12];
        let expected = Ipv4Addr::new(169, 150, 197, 173);
        let cookie = STUN_MAGIC_COOKIE.to_be_bytes();
        let address = expected.octets();
        let mut packet = vec![0u8; 32];
        packet[0..2].copy_from_slice(&0x0101u16.to_be_bytes());
        packet[2..4].copy_from_slice(&12u16.to_be_bytes());
        packet[4..8].copy_from_slice(&cookie);
        packet[8..20].copy_from_slice(&transaction_id);
        packet[20..22].copy_from_slice(&0x0020u16.to_be_bytes());
        packet[22..24].copy_from_slice(&8u16.to_be_bytes());
        packet[25] = 0x01;
        packet[26..28]
            .copy_from_slice(&(50502u16 ^ (STUN_MAGIC_COOKIE >> 16) as u16).to_be_bytes());
        packet[28] = address[0] ^ cookie[0];
        packet[29] = address[1] ^ cookie[1];
        packet[30] = address[2] ^ cookie[2];
        packet[31] = address[3] ^ cookie[3];
        assert_eq!(
            parse_stun_mapped_address(&packet, &transaction_id),
            Some(IpAddr::V4(expected))
        );
    }

    #[tokio::test]
    #[ignore = "requires access to an external STUN service"]
    async fn stun_discovery_finds_a_public_address() {
        let address = discover_public_ip()
            .await
            .expect("a STUN server should return the public address");
        assert!(is_public_ip(address));
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_round_trip_preserves_identity_bytes() {
        let input = b"LanDrop remote identity test";
        let protected = protect_for_current_user(input).expect("DPAPI should protect test bytes");
        assert_ne!(protected, input);
        let plaintext =
            unprotect_for_current_user(&protected).expect("DPAPI should unprotect test bytes");
        assert_eq!(plaintext, input);
    }

    #[tokio::test]
    async fn pinned_certificate_completes_tls_handshake() {
        let identity = test_identity();
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("test listener should bind");
        let address = listener.local_addr().expect("test address should exist");
        let acceptor = TlsAcceptor::from(Arc::new(
            build_server_config(&identity).expect("server TLS config should build"),
        ));
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("test client should connect");
            acceptor
                .accept(stream)
                .await
                .expect("pinned TLS handshake should complete");
        });

        let connector = build_client_connector(identity.certificate_der)
            .expect("client TLS config should build");
        let stream = TcpStream::connect(address)
            .await
            .expect("test client should connect");
        let server_name =
            ServerName::try_from(REMOTE_SERVER_NAME.to_string()).expect("server name should parse");
        connector
            .connect(server_name, stream)
            .await
            .expect("client should trust the pinned certificate");
        server.await.expect("server task should finish");
    }

    #[tokio::test]
    async fn remote_tunnel_relays_authenticated_control_frames() {
        let identity = test_identity();
        let local_backend = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("local backend should bind");
        let local_backend_port = local_backend
            .local_addr()
            .expect("local backend address should exist")
            .port();
        let backend = tokio::spawn(async move {
            let (mut stream, _) = local_backend
                .accept()
                .await
                .expect("remote bridge should connect");
            assert!(matches!(
                read_message(&mut stream)
                    .await
                    .expect("backend should receive peer hello"),
                ControlMessage::PeerHelloRequest(_)
            ));
            write_message(
                &mut stream,
                &ControlMessage::PeerHelloResponse(PeerHelloResponseMessage::new(
                    "remote-device".to_string(),
                    "remote-instance".to_string(),
                    "Remote PC".to_string(),
                    "desktop".to_string(),
                    50501,
                    Availability::Available,
                    false,
                )),
            )
            .await
            .expect("backend should send peer hello response");
        });

        let remote_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("remote listener should bind");
        let remote_address = remote_listener
            .local_addr()
            .expect("remote address should exist");
        let acceptor = TlsAcceptor::from(Arc::new(
            build_server_config(&identity).expect("server TLS config should build"),
        ));
        let server_identity = identity.clone();
        let remote_server = tokio::spawn(async move {
            let (stream, _) = remote_listener
                .accept()
                .await
                .expect("tunnel should connect");
            handle_remote_connection(
                acceptor,
                server_identity,
                stream,
                local_backend_port,
                None,
            )
            .await
            .expect("remote bridge should finish");
        });

        let tunnel_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("local tunnel should bind");
        let tunnel_address = tunnel_listener
            .local_addr()
            .expect("tunnel address should exist");
        let connector = build_client_connector(identity.certificate_der.clone())
            .expect("client TLS config should build");
        let invite = RemoteInvite {
            version: REMOTE_PROTOCOL_VERSION,
            device_name: "Remote PC".to_string(),
            host: remote_address.ip().to_string(),
            port: remote_address.port(),
            certificate_der: URL_SAFE_NO_PAD.encode(&identity.certificate_der),
            certificate_fingerprint: certificate_fingerprint(&identity.certificate_der),
            access_token: identity.access_token,
        };
        let tunnel = tokio::spawn(async move {
            let (stream, _) = tunnel_listener
                .accept()
                .await
                .expect("local client should connect");
            tunnel_connection(stream, connector, invite)
                .await
                .expect("tunnel should relay the session");
        });

        let mut client = TcpStream::connect(tunnel_address)
            .await
            .expect("client should connect to local tunnel");
        write_message(
            &mut client,
            &ControlMessage::PeerHelloRequest(PeerHelloRequestMessage::new()),
        )
        .await
        .expect("client should send peer hello");
        assert!(matches!(
            read_message(&mut client)
                .await
                .expect("client should receive peer hello response"),
            ControlMessage::PeerHelloResponse(response) if response.device_name == "Remote PC"
        ));
        drop(client);

        tunnel.await.expect("tunnel task should finish");
        remote_server
            .await
            .expect("remote server task should finish");
        backend.await.expect("backend task should finish");
    }
}
