use std::net::Ipv4Addr;
use std::str::FromStr;

use serde::{Deserialize, Serialize};

use crate::discovery::adapter::AdapterKind;
use crate::types::{now_ms, Availability, PeerDevice};

pub const DISCOVERY_APP: &str = "landrop";
pub const DISCOVERY_PROTOCOL: &str = "discovery";
pub const DISCOVERY_VERSION: u16 = 1;

#[derive(Debug, Clone)]
pub struct DiscoveryIdentity {
    pub device_id: String,
    pub instance_id: String,
    pub device_name: String,
    pub device_kind: String,
    pub tcp_port: u16,
    pub availability: Availability,
    pub requires_pair_code: bool,
    pub addresses: Vec<AdvertisedAddress>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdvertisedAddress {
    pub ip: String,
    pub adapter_kind: AdapterKind,
    pub priority: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DiscoveryPacketKind {
    Query,
    Hello,
    Response,
    Goodbye,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryPacket {
    pub app: String,
    pub protocol: String,
    pub version: u16,
    #[serde(rename = "type")]
    pub kind: DiscoveryPacketKind,
    pub device_id: String,
    pub instance_id: String,
    pub device_name: String,
    #[serde(default = "default_device_kind")]
    pub device_kind: String,
    pub tcp_port: u16,
    pub availability: Availability,
    pub requires_pair_code: bool,
    pub addresses: Vec<AdvertisedAddress>,
    pub timestamp_ms: u64,
}

impl DiscoveryPacket {
    pub fn from_identity(identity: DiscoveryIdentity, kind: DiscoveryPacketKind) -> Self {
        Self {
            app: DISCOVERY_APP.to_string(),
            protocol: DISCOVERY_PROTOCOL.to_string(),
            version: DISCOVERY_VERSION,
            kind,
            device_id: identity.device_id,
            instance_id: identity.instance_id,
            device_name: identity.device_name,
            device_kind: identity.device_kind,
            tcp_port: identity.tcp_port,
            availability: identity.availability,
            requires_pair_code: identity.requires_pair_code,
            addresses: identity.addresses,
            timestamp_ms: now_ms(),
        }
    }

    pub fn is_valid(&self) -> bool {
        self.app == DISCOVERY_APP
            && self.protocol == DISCOVERY_PROTOCOL
            && self.version == DISCOVERY_VERSION
    }

    pub fn to_peer(&self) -> Option<PeerDevice> {
        let best = self
            .addresses
            .iter()
            .max_by(|left, right| address_score(left).cmp(&address_score(right)))?;

        Some(PeerDevice {
            device_id: self.device_id.clone(),
            instance_id: self.instance_id.clone(),
            name: self.device_name.clone(),
            device_kind: self.device_kind.clone(),
            ip: best.ip.clone(),
            port: self.tcp_port,
            adapter_kind: best.adapter_kind,
            availability: self.availability.clone(),
            requires_pair_code: self.requires_pair_code,
            last_seen_ms: now_ms(),
            priority: best.priority,
        })
    }
}

fn default_device_kind() -> String {
    "desktop".to_string()
}

fn address_score(address: &AdvertisedAddress) -> i32 {
    let mut score = address.priority;
    if let Ok(ip) = Ipv4Addr::from_str(&address.ip) {
        if ip.is_private() {
            score += 10_000;
        } else if ip.is_link_local() {
            score -= 100;
        }
    }
    score
}
