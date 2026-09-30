use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const REMOTE_LINK_PREFIX: &str = "landrop://remote/";
pub const COMPACT_REMOTE_LINK_PREFIX: &str = "landrop://r/";
pub const REMOTE_PROTOCOL_VERSION: u16 = 1;
pub const STUN_MAGIC_COOKIE: u32 = 0x2112_A442;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteInvite {
    pub version: u16,
    pub device_name: String,
    pub host: String,
    pub port: u16,
    pub certificate_der: String,
    pub certificate_fingerprint: String,
    pub access_token: String,
}

pub fn certificate_fingerprint(certificate_der: &[u8]) -> String {
    Sha256::digest(certificate_der)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

pub fn fingerprints_equal(left: &str, right: &str) -> bool {
    left.eq_ignore_ascii_case(right)
}

pub fn decode_invite(value: &str) -> Result<RemoteInvite, String> {
    if let Some(encoded) = value.trim().strip_prefix(COMPACT_REMOTE_LINK_PREFIX) {
        return decode_compact_invite(encoded);
    }
    let encoded = value
        .trim()
        .strip_prefix(REMOTE_LINK_PREFIX)
        .ok_or_else(|| "This is not a LanDrop remote invite.".to_string())?;
    let payload = URL_SAFE_NO_PAD
        .decode(encoded.as_bytes())
        .map_err(|_| "Remote invite encoding is invalid.".to_string())?;
    let invite: RemoteInvite =
        serde_json::from_slice(&payload).map_err(|_| "Remote invite is invalid.".to_string())?;
    if invite.version != REMOTE_PROTOCOL_VERSION {
        return Err("Remote invite version is not supported.".to_string());
    }
    validate_host(&invite.host)?;
    if invite.port == 0 || invite.access_token.len() < 32 {
        return Err("Remote invite is incomplete.".to_string());
    }
    let certificate_der = URL_SAFE_NO_PAD
        .decode(invite.certificate_der.as_bytes())
        .map_err(|_| "Remote invite contains an invalid certificate.".to_string())?;
    if certificate_der.is_empty() {
        return Err("Remote invite is incomplete.".to_string());
    }
    let actual_fingerprint = certificate_fingerprint(&certificate_der);
    if !fingerprints_equal(&invite.certificate_fingerprint, &actual_fingerprint) {
        return Err("Remote invite certificate fingerprint does not match.".to_string());
    }
    Ok(RemoteInvite {
        certificate_fingerprint: actual_fingerprint,
        ..invite
    })
}

pub fn encode_compact_invite(
    invite: &RemoteInvite,
    certificate_der: &[u8],
) -> Result<String, String> {
    let host = invite.host.as_bytes();
    let device_name = utf8_prefix(invite.device_name.as_bytes(), 48);
    let token = invite.access_token.as_bytes();
    if invite.port == 0 {
        return Err("Remote invite port cannot be zero.".to_string());
    }
    if host.is_empty()
        || host.len() > u8::MAX as usize
        || device_name.len() > u8::MAX as usize
        || certificate_der.len() > u16::MAX as usize
        || token.len() > u8::MAX as usize
        || token.len() < 32
        || certificate_der.is_empty()
    {
        return Err("Remote invite is too large.".to_string());
    }
    validate_host(&invite.host)?;
    let mut payload = Vec::with_capacity(
        8 + host.len() + device_name.len() + certificate_der.len() + token.len(),
    );
    payload.push(REMOTE_PROTOCOL_VERSION as u8);
    payload.push(host.len() as u8);
    payload.extend_from_slice(host);
    payload.extend_from_slice(&invite.port.to_be_bytes());
    payload.push(device_name.len() as u8);
    payload.extend_from_slice(device_name);
    payload.extend_from_slice(&(certificate_der.len() as u16).to_be_bytes());
    payload.extend_from_slice(certificate_der);
    payload.push(token.len() as u8);
    payload.extend_from_slice(token);
    Ok(format!(
        "{COMPACT_REMOTE_LINK_PREFIX}{}",
        URL_SAFE_NO_PAD.encode(payload)
    ))
}

pub fn encode_json_invite(invite: &RemoteInvite) -> Result<String, String> {
    validate_host(&invite.host)?;
    if invite.port == 0 || invite.access_token.len() < 32 {
        return Err("Remote invite is incomplete.".to_string());
    }
    let payload = serde_json::to_vec(invite).map_err(|error| error.to_string())?;
    Ok(format!(
        "{REMOTE_LINK_PREFIX}{}",
        URL_SAFE_NO_PAD.encode(payload)
    ))
}

fn decode_compact_invite(encoded: &str) -> Result<RemoteInvite, String> {
    let payload = URL_SAFE_NO_PAD
        .decode(encoded.as_bytes())
        .map_err(|_| "Remote invite encoding is invalid.".to_string())?;
    let mut reader = CompactInviteReader::new(&payload);
    let version = reader.read_u8()? as u16;
    let host_length = reader.read_u8()? as usize;
    let host = reader.read_string(host_length)?;
    let port = reader.read_u16()?;
    let device_name_length = reader.read_u8()? as usize;
    let device_name = reader.read_string(device_name_length)?;
    let certificate_length = reader.read_u16()? as usize;
    let certificate_der = reader.read_bytes(certificate_length)?.to_vec();
    let token_length = reader.read_u8()? as usize;
    let access_token = reader.read_string(token_length)?;
    if !reader.is_finished() {
        return Err("Remote invite has unexpected trailing data.".to_string());
    }
    if version != REMOTE_PROTOCOL_VERSION {
        return Err("Remote invite version is not supported.".to_string());
    }
    validate_host(&host)?;
    if port == 0 || access_token.len() < 32 || certificate_der.is_empty() {
        return Err("Remote invite is incomplete.".to_string());
    }
    Ok(RemoteInvite {
        version,
        device_name,
        host,
        port,
        certificate_der: URL_SAFE_NO_PAD.encode(&certificate_der),
        certificate_fingerprint: certificate_fingerprint(&certificate_der),
        access_token,
    })
}

struct CompactInviteReader<'a> {
    payload: &'a [u8],
    offset: usize,
}

impl<'a> CompactInviteReader<'a> {
    fn new(payload: &'a [u8]) -> Self {
        Self { payload, offset: 0 }
    }

    fn read_u8(&mut self) -> Result<u8, String> {
        let value = *self
            .payload
            .get(self.offset)
            .ok_or_else(|| "Remote invite is truncated.".to_string())?;
        self.offset += 1;
        Ok(value)
    }

    fn read_u16(&mut self) -> Result<u16, String> {
        let end = self
            .offset
            .checked_add(2)
            .ok_or_else(|| "Remote invite is truncated.".to_string())?;
        let bytes = self
            .payload
            .get(self.offset..end)
            .ok_or_else(|| "Remote invite is truncated.".to_string())?;
        self.offset = end;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn read_bytes(&mut self, length: usize) -> Result<&'a [u8], String> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or_else(|| "Remote invite is truncated.".to_string())?;
        let bytes = self
            .payload
            .get(self.offset..end)
            .ok_or_else(|| "Remote invite is truncated.".to_string())?;
        self.offset = end;
        Ok(bytes)
    }

    fn read_string(&mut self, length: usize) -> Result<String, String> {
        let bytes = self.read_bytes(length)?;
        String::from_utf8(bytes.to_vec()).map_err(|_| "Remote invite text is invalid.".to_string())
    }

    fn is_finished(&self) -> bool {
        self.offset == self.payload.len()
    }
}

fn utf8_prefix(bytes: &[u8], max_length: usize) -> &[u8] {
    let mut length = bytes.len().min(max_length);
    while length > 0 && std::str::from_utf8(&bytes[..length]).is_err() {
        length -= 1;
    }
    &bytes[..length]
}

pub fn validate_host(value: &str) -> Result<String, String> {
    let host = value.trim();
    if host.is_empty() || host.len() > 253 {
        return Err("Enter the router public IP address or DNS name.".to_string());
    }
    if host.parse::<Ipv6Addr>().is_ok() {
        return Ok(host.to_string());
    }
    if !host
        .chars()
        .all(|character| character.is_ascii_alphanumeric() || ".:-".contains(character))
    {
        return Err("The public host contains unsupported characters.".to_string());
    }
    Ok(host.to_string())
}

pub fn parse_public_endpoint(value: &str, default_port: u16) -> Result<(String, u16), String> {
    let endpoint = value.trim();
    if endpoint.is_empty() {
        return Err("Enter the router public IP address or DNS name.".to_string());
    }
    if endpoint.starts_with('[') {
        if let Ok(address) = endpoint.parse::<SocketAddr>() {
            if address.port() == 0 {
                return Err("Remote port cannot be zero.".to_string());
            }
            return Ok((address.ip().to_string(), address.port()));
        }
        if endpoint.ends_with(']') {
            let host = &endpoint[1..endpoint.len() - 1];
            if host.parse::<Ipv6Addr>().is_ok() {
                if default_port == 0 {
                    return Err("Remote port cannot be zero.".to_string());
                }
                return Ok((host.to_string(), default_port));
            }
        }
        return Err("Enter an IPv6 address as [host] or [host]:port.".to_string());
    }
    if endpoint.matches(':').count() == 1 {
        if let Some((host, port)) = endpoint.rsplit_once(':') {
            return match port.parse::<u16>() {
                Ok(0) => Err("Remote port cannot be zero.".to_string()),
                Ok(port) => Ok((validate_host(host)?, port)),
                Err(_) => Err("Enter a valid public host or host:port.".to_string()),
            };
        }
    }
    if endpoint.parse::<Ipv6Addr>().is_ok() {
        if default_port == 0 {
            return Err("Remote port cannot be zero.".to_string());
        }
        return Ok((endpoint.to_string(), default_port));
    }
    if endpoint.contains(':') {
        return Err("Enter an IPv6 address as [host] or [host]:port.".to_string());
    }
    if default_port == 0 {
        return Err("Remote port cannot be zero.".to_string());
    }
    Ok((validate_host(endpoint)?, default_port))
}

pub fn parse_stun_mapped_address(packet: &[u8], transaction_id: &[u8]) -> Option<IpAddr> {
    if packet.len() < 20
        || u16::from_be_bytes([packet[0], packet[1]]) != 0x0101
        || u32::from_be_bytes([packet[4], packet[5], packet[6], packet[7]]) != STUN_MAGIC_COOKIE
        || packet.get(8..20)? != transaction_id
    {
        return None;
    }
    let message_length = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    let end = 20usize.checked_add(message_length)?.min(packet.len());
    let mut offset = 20;
    while offset + 4 <= end {
        let attribute_type = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
        let attribute_length =
            u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]) as usize;
        let value_start = offset + 4;
        let value_end = value_start.checked_add(attribute_length)?;
        if value_end > end {
            return None;
        }
        let value = &packet[value_start..value_end];
        if value.len() >= 8 && (attribute_type == 0x0020 || attribute_type == 0x0001) {
            match value[1] {
                0x01 => {
                    let address = if attribute_type == 0x0020 {
                        let cookie = STUN_MAGIC_COOKIE.to_be_bytes();
                        Ipv4Addr::new(
                            value[4] ^ cookie[0],
                            value[5] ^ cookie[1],
                            value[6] ^ cookie[2],
                            value[7] ^ cookie[3],
                        )
                    } else {
                        Ipv4Addr::new(value[4], value[5], value[6], value[7])
                    };
                    return Some(IpAddr::V4(address));
                }
                0x02 if value.len() >= 20 => {
                    let mut octets = [0u8; 16];
                    octets.copy_from_slice(&value[4..20]);
                    if attribute_type == 0x0020 {
                        let cookie = STUN_MAGIC_COOKIE.to_be_bytes();
                        for (index, octet) in octets.iter_mut().enumerate() {
                            let mask = if index < 4 {
                                cookie[index]
                            } else {
                                transaction_id[index - 4]
                            };
                            *octet ^= mask;
                        }
                    }
                    return Some(IpAddr::V6(Ipv6Addr::from(octets)));
                }
                _ => {}
            }
        }
        offset = value_end + ((4 - attribute_length % 4) % 4);
    }
    None
}

pub fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let octets = ip.octets();
            let shared_address_space = octets[0] == 100 && (64..=127).contains(&octets[1]);
            !ip.is_private()
                && !ip.is_loopback()
                && !ip.is_link_local()
                && !ip.is_broadcast()
                && !ip.is_documentation()
                && !ip.is_unspecified()
                && !shared_address_space
        }
        IpAddr::V6(ip) => {
            let segments = ip.segments();
            let unique_local = (segments[0] & 0xfe00) == 0xfc00;
            let link_local = (segments[0] & 0xffc0) == 0xfe80;
            !ip.is_loopback()
                && !ip.is_unspecified()
                && !unique_local
                && !link_local
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compact_invite_round_trip_preserves_pinned_identity() {
        let certificate = (0..64u8).collect::<Vec<_>>();
        let invite = RemoteInvite {
            version: REMOTE_PROTOCOL_VERSION,
            device_name: "Home PC".to_string(),
            host: "203.0.113.25".to_string(),
            port: 50502,
            certificate_der: URL_SAFE_NO_PAD.encode(&certificate),
            certificate_fingerprint: certificate_fingerprint(&certificate),
            access_token: "test-access-token-that-is-long-enough".to_string(),
        };
        let encoded =
            encode_compact_invite(&invite, &certificate).expect("invite should encode");
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
    fn json_invite_accepts_uppercase_fingerprint() {
        let certificate = (0..48u8).collect::<Vec<_>>();
        let fingerprint = certificate_fingerprint(&certificate).to_uppercase();
        let invite = RemoteInvite {
            version: REMOTE_PROTOCOL_VERSION,
            device_name: "Office PC".to_string(),
            host: "vpn.example.net".to_string(),
            port: 42817,
            certificate_der: URL_SAFE_NO_PAD.encode(&certificate),
            certificate_fingerprint: fingerprint,
            access_token: "0123456789abcdef0123456789abcdef".to_string(),
        };
        let encoded = encode_json_invite(&invite).expect("json invite should encode");
        let decoded = decode_invite(&encoded).expect("json invite should decode");
        assert_eq!(decoded.host, "vpn.example.net");
        assert_eq!(decoded.port, 42817);
        assert_eq!(
            decoded.certificate_fingerprint,
            certificate_fingerprint(&certificate)
        );
    }

    #[test]
    fn public_endpoint_parsing_covers_ipv4_ipv6_and_rejects_invalid() {
        assert_eq!(
            parse_public_endpoint("vpn.example.net:42817", 50502).unwrap(),
            ("vpn.example.net".to_string(), 42817)
        );
        assert_eq!(
            parse_public_endpoint("203.0.113.25", 50502).unwrap(),
            ("203.0.113.25".to_string(), 50502)
        );
        assert_eq!(
            parse_public_endpoint("[2001:db8::1]:50502", 50502).unwrap(),
            ("2001:db8::1".to_string(), 50502)
        );
        assert_eq!(
            parse_public_endpoint("[2001:db8::1]", 50502).unwrap(),
            ("2001:db8::1".to_string(), 50502)
        );
        assert_eq!(
            parse_public_endpoint("2001:db8::1", 50502).unwrap(),
            ("2001:db8::1".to_string(), 50502)
        );
        assert!(parse_public_endpoint("vpn.example.net:abc", 50502).is_err());
        assert!(parse_public_endpoint("vpn.example.net:", 50502).is_err());
        assert!(parse_public_endpoint("vpn.example.net:99999", 50502).is_err());
        assert!(parse_public_endpoint("203.0.113.25:0", 50502).is_err());
        assert!(parse_public_endpoint("2001:db8::1:50502", 50502).is_err());
    }

    #[test]
    fn stun_parser_decodes_xor_mapped_ipv4_and_ipv6() {
        let transaction_id = [7u8; 12];
        let expected_v4 = Ipv4Addr::new(169, 150, 197, 173);
        let cookie = STUN_MAGIC_COOKIE.to_be_bytes();
        let address = expected_v4.octets();
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
            Some(IpAddr::V4(expected_v4))
        );

        let expected_v6 = Ipv6Addr::new(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1);
        let mut v6_packet = vec![0u8; 44];
        v6_packet[0..2].copy_from_slice(&0x0101u16.to_be_bytes());
        v6_packet[2..4].copy_from_slice(&24u16.to_be_bytes());
        v6_packet[4..8].copy_from_slice(&cookie);
        v6_packet[8..20].copy_from_slice(&transaction_id);
        v6_packet[20..22].copy_from_slice(&0x0020u16.to_be_bytes());
        v6_packet[22..24].copy_from_slice(&20u16.to_be_bytes());
        v6_packet[25] = 0x02;
        v6_packet[26..28]
            .copy_from_slice(&(50502u16 ^ (STUN_MAGIC_COOKIE >> 16) as u16).to_be_bytes());
        let mut octets = expected_v6.octets();
        for (index, octet) in octets.iter_mut().enumerate() {
            let mask = if index < 4 {
                cookie[index]
            } else {
                transaction_id[index - 4]
            };
            *octet ^= mask;
        }
        v6_packet[28..44].copy_from_slice(&octets);
        assert_eq!(
            parse_stun_mapped_address(&v6_packet, &transaction_id),
            Some(IpAddr::V6(expected_v6))
        );
    }
}
