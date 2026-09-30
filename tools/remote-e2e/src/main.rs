//! Production-protocol remote host/sender harness for emulator E2E.
//! Speaks the same control framing Android RustBridge uses:
//!   type=peer_hello_request / peer_hello_response / transfer_offer / transfer_response

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rcgen::{generate_simple_self_signed, CertifiedKey};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{ClientConfig, RootCertStore, ServerConfig};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use subtle::ConstantTimeEq;
use tokio::io::{copy_bidirectional, AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream, UdpSocket};
use tokio::time::timeout;
use tokio_rustls::{TlsAcceptor, TlsConnector};

#[path = "../../../src-tauri/src/remote_codec.rs"]
mod remote_codec;

use remote_codec::{
    certificate_fingerprint, decode_invite, encode_compact_invite, is_public_ip,
    parse_stun_mapped_address, RemoteInvite, REMOTE_PROTOCOL_VERSION, STUN_MAGIC_COOKIE,
};

const REMOTE_SERVER_NAME: &str = "landrop.remote";
const MAX_REMOTE_HANDSHAKE: usize = 8 * 1024;
const MAX_CONTROL_FRAME: usize = 16 * 1024 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

fn is_preflight_close(error: &str) -> bool {
    error.contains("early eof")
        || error.contains("UnexpectedEof")
        || error.contains("close_notify")
        || error.contains("connection reset")
        || error.contains("Broken pipe")
        || error.contains("failed to fill whole buffer")
}

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

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1).collect::<Vec<_>>();
    if args.is_empty() {
        eprintln!(
            "usage:\n  landrop-remote-e2e host --bind <ip> --port <port> --invite <path> --public-host <ip>\n  landrop-remote-e2e sender --invite <path> [--preflight] [--expect-name <name>]\n  landrop-remote-e2e stun"
        );
        std::process::exit(2);
    }
    let mode = args.remove(0);
    let result = match mode.as_str() {
        "host" => run_host(&args).await,
        "sender" => run_sender(&args).await,
        "stun" => run_stun().await,
        other => Err(format!("unknown mode: {other}")),
    };
    if let Err(error) = result {
        eprintln!("{} FAIL: {error}", mode.to_uppercase());
        std::process::exit(1);
    }
}

fn flag(args: &[String], name: &str) -> Option<String> {
    args.windows(2)
        .find(|window| window[0] == name)
        .map(|window| window[1].clone())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

async fn run_host(args: &[String]) -> Result<(), String> {
    let bind = flag(args, "--bind").unwrap_or_else(|| "0.0.0.0".to_string());
    let port = flag(args, "--port")
        .unwrap_or_else(|| "50502".to_string())
        .parse::<u16>()
        .map_err(|_| "invalid --port".to_string())?;
    let invite_path = PathBuf::from(flag(args, "--invite").ok_or("--invite is required")?);
    let public_host = flag(args, "--public-host").unwrap_or_else(|| {
        if bind == "0.0.0.0" {
            "127.0.0.1".to_string()
        } else {
            bind.clone()
        }
    });
    let device_name = flag(args, "--name").unwrap_or_else(|| "Host PC".to_string());
    let ready_path = flag(args, "--ready").map(PathBuf::from);

    let emulators = flag(args, "--emulators")
        .or_else(|| flag(args, "--sessions"))
        .unwrap_or_else(|| "1".to_string())
        .parse::<usize>()
        .unwrap_or(1)
        .max(1);
    // Each Android tunnel does one preflight TLS authorize + one probe TLS session.
    let tls_sessions = flag(args, "--tls-sessions")
        .map(|value| value.parse::<usize>().unwrap_or(0))
        .filter(|value| *value > 0)
        .unwrap_or(emulators * 2);
    let probe_sessions = flag(args, "--probe-sessions")
        .map(|value| value.parse::<usize>().unwrap_or(0))
        .filter(|value| *value > 0)
        .unwrap_or(emulators);
    let identity = create_identity()?;
    let local_listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|error| error.to_string())?;
    let local_port = local_listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();

    let device_name_for_backend = device_name.clone();
    tokio::spawn(async move {
        let _ = run_local_backend(local_listener, device_name_for_backend, probe_sessions).await;
    });

    let remote_listener = TcpListener::bind((bind.as_str(), port))
        .await
        .map_err(|error| format!("unable to bind remote listener on {bind}:{port}: {error}"))?;

    let invite = RemoteInvite {
        version: REMOTE_PROTOCOL_VERSION,
        device_name: device_name.clone(),
        host: public_host.clone(),
        port,
        certificate_der: URL_SAFE_NO_PAD.encode(&identity.certificate_der),
        certificate_fingerprint: certificate_fingerprint(&identity.certificate_der),
        access_token: identity.access_token.clone(),
    };
    let encoded = encode_compact_invite(&invite, &identity.certificate_der)?;
    tokio::fs::write(&invite_path, format!("{encoded}\n"))
        .await
        .map_err(|error| error.to_string())?;
    if let Some(path) = ready_path {
        tokio::fs::write(&path, "ready\n")
            .await
            .map_err(|error| error.to_string())?;
    }
    println!(
        "HOST READY invite={} endpoint={}:{} emulators={emulators} tls_sessions={tls_sessions}",
        invite_path.display(),
        invite.host,
        invite.port
    );

    let server_config = build_server_config(&identity)?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));
    let mut joins = Vec::new();
    for index in 0..tls_sessions {
        let (stream, peer) = remote_listener
            .accept()
            .await
            .map_err(|error| error.to_string())?;
        println!("HOST accepted remote TCP #{index} from {peer}");
        let acceptor = acceptor.clone();
        let identity = identity.clone();
        joins.push(tokio::spawn(async move {
            handle_remote_connection(acceptor, identity, stream, local_port).await
        }));
    }
    for join in joins {
        join.await
            .map_err(|error| error.to_string())?
            .map_err(|error| error)?;
    }
    println!("HOST PASS emulators={emulators}");
    Ok(())
}

async fn run_local_backend(
    listener: TcpListener,
    device_name: String,
    sessions: usize,
) -> Result<usize, String> {
    let mut handled = 0usize;
    while handled < sessions {
        let (mut stream, _) = timeout(Duration::from_secs(180), listener.accept())
            .await
            .map_err(|_| "timed out waiting for local tunnel probe".to_string())?
            .map_err(|error| error.to_string())?;
        let request = read_json_value(&mut stream).await?;
        if request.get("type").and_then(Value::as_str) != Some("peer_hello_request") {
            return Err(format!("unexpected first frame: {request}"));
        }
        let response = json!({
            "type": "peer_hello_response",
            "protocolVersion": 1,
            "deviceId": "host-device-e2e",
            "instanceId": "host-instance-e2e",
            "deviceName": device_name,
            "deviceKind": "desktop",
            "tcpPort": 50501,
            "availability": "available",
            "requiresPairCode": false,
            "respondedAtMs": now_ms()
        });
        write_json_value(&mut stream, &response).await?;
        handled += 1;
    }
    Ok(handled)
}

async fn handle_remote_connection(
    acceptor: TlsAcceptor,
    identity: RemoteIdentity,
    stream: TcpStream,
    local_transfer_port: u16,
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

    write_remote_message(
        &mut tls_stream,
        &RemoteAuthResponse {
            accepted: true,
            message: None,
        },
    )
    .await?;

    let mut length = [0u8; 4];
    match timeout(HANDSHAKE_TIMEOUT, tls_stream.read_exact(&mut length)).await {
        Err(_) => return Err("Remote protocol negotiation timed out.".to_string()),
        Ok(Err(error)) => {
            // Android preflight authorizes then closes without sending a control frame.
            if is_preflight_close(&error.to_string()) {
                return Ok(());
            }
            return Err(error.to_string());
        }
        Ok(Ok(_)) => {}
    }
    let frame_len = u32::from_be_bytes(length) as usize;
    if frame_len == 0 || frame_len > MAX_CONTROL_FRAME {
        return Err("Remote first frame is invalid.".to_string());
    }
    let mut frame = vec![0u8; frame_len];
    tls_stream
        .read_exact(&mut frame)
        .await
        .map_err(|error| error.to_string())?;

    let mut local_stream = TcpStream::connect(("127.0.0.1", local_transfer_port))
        .await
        .map_err(|error| format!("Local transfer listener is unavailable: {error}"))?;
    local_stream
        .write_all(&length)
        .await
        .map_err(|error| error.to_string())?;
    local_stream
        .write_all(&frame)
        .await
        .map_err(|error| error.to_string())?;
    local_stream.flush().await.map_err(|error| error.to_string())?;

    match copy_bidirectional(&mut tls_stream, &mut local_stream).await {
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

async fn run_sender(args: &[String]) -> Result<(), String> {
    let invite_path = PathBuf::from(flag(args, "--invite").ok_or("--invite is required")?);
    let expect_name = flag(args, "--expect-name");
    let preflight = args.iter().any(|arg| arg == "--preflight");
    let encoded = tokio::fs::read_to_string(&invite_path)
        .await
        .map_err(|error| error.to_string())?;
    let invite = decode_invite(encoded.trim())?;
    println!(
        "SENDER using invite host={}:{} device={}",
        invite.host, invite.port, invite.device_name
    );

    if preflight {
        run_sender_session(&invite, false, None).await?;
    }
    let hello = run_sender_session(&invite, true, expect_name.as_deref()).await?;
    println!(
        "SENDER PASS device={}",
        hello
            .get("deviceName")
            .and_then(Value::as_str)
            .unwrap_or("?")
    );
    Ok(())
}

async fn run_sender_session(
    invite: &RemoteInvite,
    probe: bool,
    expect_name: Option<&str>,
) -> Result<Value, String> {
    let certificate_der = URL_SAFE_NO_PAD
        .decode(invite.certificate_der.as_bytes())
        .map_err(|_| "invite certificate is invalid".to_string())?;
    let connector = build_client_connector(certificate_der)?;
    let remote_stream = timeout(
        Duration::from_secs(12),
        TcpStream::connect((invite.host.as_str(), invite.port)),
    )
    .await
    .map_err(|_| "Remote computer connection timed out.".to_string())?
    .map_err(|error| format!("Remote computer is unreachable: {error}"))?;
    let server_name = ServerName::try_from(REMOTE_SERVER_NAME.to_string())
        .map_err(|error| error.to_string())?;
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
            access_token: invite.access_token.clone(),
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
    if !probe {
        println!("SENDER preflight authorized over TLS");
        return Ok(Value::Null);
    }
    println!("SENDER authorized over TLS");

    write_json_value(
        &mut tls_stream,
        &json!({
            "type": "peer_hello_request",
            "protocolVersion": 1,
            "requestedAtMs": now_ms()
        }),
    )
    .await?;
    let hello = read_json_value(&mut tls_stream).await?;
    if hello.get("type").and_then(Value::as_str) != Some("peer_hello_response") {
        return Err(format!("unexpected hello response: {hello}"));
    }
    if let Some(expected) = expect_name {
        let actual = hello
            .get("deviceName")
            .and_then(Value::as_str)
            .unwrap_or("");
        if actual != expected {
            return Err(format!("unexpected device name: {actual}"));
        }
    }
    let _ = tls_stream.shutdown().await;
    Ok(hello)
}

const STUN_SERVERS: &[(&str, u16)] = &[("stun.cloudflare.com", 3478), ("stun.l.google.com", 19302)];

async fn run_stun() -> Result<(), String> {
    let public_ip = discover_public_ip()
        .await
        .ok_or_else(|| "STUN discovery did not return a public address".to_string())?;
    println!("STUN PASS public_ip={public_ip}");
    Ok(())
}

async fn discover_public_ip() -> Option<std::net::IpAddr> {
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
            let transaction_id = rand_transaction_id();
            let mut request = [0u8; 20];
            request[0..2].copy_from_slice(&0x0001u16.to_be_bytes());
            request[4..8].copy_from_slice(&STUN_MAGIC_COOKIE.to_be_bytes());
            request[8..20].copy_from_slice(&transaction_id);
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
            if let Some(ip) = parse_stun_mapped_address(&response[..length], &transaction_id) {
                if is_public_ip(ip) {
                    return Some(ip);
                }
            }
        }
    }
    None
}

fn rand_transaction_id() -> [u8; 12] {
    let seed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let mut id = [0u8; 12];
    for (index, byte) in id.iter_mut().enumerate() {
        *byte = ((seed >> (index * 5)) & 0xFF) as u8;
    }
    id
}

fn create_identity() -> Result<RemoteIdentity, String> {
    let CertifiedKey { cert, key_pair } =
        generate_simple_self_signed(vec![REMOTE_SERVER_NAME.to_string()])
            .map_err(|error| error.to_string())?;
    Ok(RemoteIdentity {
        certificate_der: cert.der().to_vec(),
        private_key_der: key_pair.serialize_der(),
        access_token: "e2e-access-token-0123456789abcdef".to_string(),
    })
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

async fn write_remote_message<W, T>(stream: &mut W, message: &T) -> Result<(), String>
where
    W: AsyncWriteExt + Unpin,
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
    R: AsyncReadExt + Unpin,
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

async fn write_json_value<W>(stream: &mut W, message: &Value) -> Result<(), String>
where
    W: AsyncWriteExt + Unpin,
{
    let payload = serde_json::to_vec(message).map_err(|error| error.to_string())?;
    if payload.len() > MAX_CONTROL_FRAME {
        return Err("Control message too large.".to_string());
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

async fn read_json_value<R>(stream: &mut R) -> Result<Value, String>
where
    R: AsyncReadExt + Unpin,
{
    let mut length = [0u8; 4];
    stream
        .read_exact(&mut length)
        .await
        .map_err(|error| error.to_string())?;
    let length = u32::from_be_bytes(length) as usize;
    if length > MAX_CONTROL_FRAME {
        return Err("Control message too large.".to_string());
    }
    let mut payload = vec![0u8; length];
    stream
        .read_exact(&mut payload)
        .await
        .map_err(|error| error.to_string())?;
    serde_json::from_slice(&payload).map_err(|error| error.to_string())
}
