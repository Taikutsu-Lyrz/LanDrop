use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpStream, ToSocketAddrs},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use jni::{
    objects::{JClass, JString},
    sys::{jint, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

const MAX_CONTROL_FRAME: usize = 16 * 1024 * 1024;
const CONTROL_PROTOCOL_VERSION: u16 = 1;

#[no_mangle]
pub extern "system" fn Java_dev_landrop_mobile_RustBridge_probePeer(
    mut env: JNIEnv,
    _class: JClass,
    ip: JString,
    port: jint,
) -> jstring {
    let response = match env.get_string(&ip) {
        Ok(value) => {
            let ip: String = value.into();
            probe_peer(&ip, port as u16)
        }
        Err(error) => json!({
            "ok": false,
            "error": format!("Could not read IP address: {error}")
        })
        .to_string(),
    };

    match env.new_string(response) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_landrop_mobile_RustBridge_sendOffer(
    mut env: JNIEnv,
    _class: JClass,
    ip: JString,
    port: jint,
    sender_name: JString,
    payload_json: JString,
) -> jstring {
    let response = match (
        env.get_string(&ip),
        env.get_string(&sender_name),
        env.get_string(&payload_json),
    ) {
        (Ok(ip), Ok(sender_name), Ok(payload_json)) => send_offer(
            &String::from(ip),
            port as u16,
            &String::from(sender_name),
            &String::from(payload_json),
        ),
        _ => json!({
            "ok": false,
            "error": "Could not read send request."
        })
        .to_string(),
    };

    match env.new_string(response) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn probe_peer(host: &str, port: u16) -> String {
    let host = host.trim();
    if host.is_empty() {
        return json!({
            "ok": false,
            "error": "Enter a valid IP address or hostname."
        })
        .to_string();
    }
    // Resolve hostnames (Tailscale MagicDNS, mDNS, VPN names) as well as IPs.
    let addrs: Vec<SocketAddr> = match format!("{host}:{port}").parse() {
        Ok(addr) => vec![addr],
        Err(_) => match format!("{host}:{port}")
            .to_socket_addrs()
            .map(|iter| iter.collect::<Vec<_>>()) {
            Ok(list) if !list.is_empty() => list,
            _ => {
                return json!({
                    "ok": false,
                    "error": format!("Could not resolve {host}.")
                })
                .to_string()
            }
        },
    };

    let mut last_error = String::new();
    for target in addrs {
        match probe_peer_inner(target) {
            Ok(peer) => return peer,
            Err(error) => last_error = error,
        }
    }
    json!({
        "ok": false,
        "error": last_error
    })
    .to_string()
}

fn probe_peer_inner(target: SocketAddr) -> Result<String, String> {
    let mut stream = TcpStream::connect_timeout(&target, Duration::from_secs(5))
        .map_err(|error| format!("Could not connect to {target}: {error}"))?;

    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|error| error.to_string())?;
    stream.set_nodelay(true).ok();

    let hello = json!({
        "type": "peer_hello_request",
        "protocolVersion": CONTROL_PROTOCOL_VERSION,
        "requestedAtMs": now_ms()
    });
    write_message(&mut stream, &hello)?;
    let response = read_message(&mut stream)?;

    if response.get("type").and_then(Value::as_str) != Some("peer_hello_response") {
        return Err("Remote device answered with an unexpected message.".to_string());
    }

    Ok(json!({
        "ok": true,
        "deviceId": response.get("deviceId").and_then(Value::as_str).unwrap_or(""),
        "instanceId": response.get("instanceId").and_then(Value::as_str).unwrap_or(""),
        "name": response.get("deviceName").and_then(Value::as_str).unwrap_or("LanDrop PC"),
        "ip": target.ip().to_string(),
        "port": response.get("tcpPort").and_then(Value::as_u64).unwrap_or(target.port() as u64),
        "availability": response.get("availability").and_then(Value::as_str).unwrap_or("available"),
        "requiresPairCode": response.get("requiresPairCode").and_then(Value::as_bool).unwrap_or(false),
        "deviceKind": response.get("deviceKind").and_then(Value::as_str).unwrap_or("desktop")
    })
    .to_string())
}

fn send_offer(host: &str, port: u16, sender_name: &str, payload_json: &str) -> String {
    let target: SocketAddr = match format!("{host}:{port}").parse() {
        Ok(value) => value,
        Err(_) => match format!("{host}:{port}")
            .to_socket_addrs()
            .ok()
            .and_then(|mut iter| iter.next()) {
            Some(value) => value,
            None => {
                return json!({
                    "ok": false,
                    "error": format!("Could not resolve {host}.")
                })
                .to_string()
            }
        },
    };

    match send_offer_inner(target, sender_name, payload_json) {
        Ok(response) => response,
        Err(error) => json!({
            "ok": false,
            "error": error
        })
        .to_string(),
    }
}

fn send_offer_inner(
    target: SocketAddr,
    sender_name: &str,
    payload_json: &str,
) -> Result<String, String> {
    let payload: Value = serde_json::from_str(payload_json).map_err(|error| error.to_string())?;
    let transfer_id = format!("android-{}", now_ms());
    let top_level_items = payload
        .get("topLevelItems")
        .cloned()
        .unwrap_or_else(|| Value::Array(Vec::new()));

    let offer = json!({
        "type": "transfer_offer",
        "protocolVersion": CONTROL_PROTOCOL_VERSION,
        "transferId": transfer_id,
        "senderDeviceId": "landrop-android",
        "senderName": sender_name,
        "receiverDeviceId": null,
        "pairCode": null,
        "fileCount": payload.get("fileCount").and_then(Value::as_u64).unwrap_or(0),
        "directoryCount": payload.get("directoryCount").and_then(Value::as_u64).unwrap_or(0),
        "totalBytes": payload.get("totalBytes").and_then(Value::as_u64).unwrap_or(0),
        "requiresPairCode": false,
        "topLevelItems": top_level_items,
        "sentAtMs": now_ms()
    });

    let mut stream = TcpStream::connect_timeout(&target, Duration::from_secs(5))
        .map_err(|error| format!("Could not connect to {target}: {error}"))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(300)))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|error| error.to_string())?;
    stream.set_nodelay(true).ok();

    write_message(&mut stream, &offer)?;
    let response = read_message(&mut stream)?;
    if response.get("type").and_then(Value::as_str) != Some("transfer_response") {
        return Err("Remote device answered with an unexpected message.".to_string());
    }

    Ok(json!({
        "ok": true,
        "transferId": response.get("transferId").and_then(Value::as_str).unwrap_or(""),
        "accepted": response.get("accepted").and_then(Value::as_bool).unwrap_or(false),
        "message": response.get("message").and_then(Value::as_str).unwrap_or("")
    })
    .to_string())
}

fn write_message(stream: &mut TcpStream, message: &Value) -> Result<(), String> {
    let payload = serde_json::to_vec(message).map_err(|error| error.to_string())?;
    if payload.len() > MAX_CONTROL_FRAME {
        return Err("Control message too large.".to_string());
    }

    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .map_err(|error| error.to_string())?;
    stream
        .write_all(&payload)
        .map_err(|error| error.to_string())?;
    stream.flush().map_err(|error| error.to_string())
}

fn read_message(stream: &mut TcpStream) -> Result<Value, String> {
    let mut length_buf = [0_u8; 4];
    stream
        .read_exact(&mut length_buf)
        .map_err(|error| error.to_string())?;
    let length = u32::from_be_bytes(length_buf) as usize;
    if length > MAX_CONTROL_FRAME {
        return Err("Control message too large.".to_string());
    }

    let mut payload = vec![0_u8; length];
    stream
        .read_exact(&mut payload)
        .map_err(|error| error.to_string())?;
    serde_json::from_slice(&payload).map_err(|error| error.to_string())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
