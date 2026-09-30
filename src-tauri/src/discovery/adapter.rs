use std::net::Ipv4Addr;

use serde::{Deserialize, Serialize};

use crate::types::{LocalAdapter, LocalAdapterStatus};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AdapterKind {
    Ethernet,
    EthernetDirect,
    Wifi,
    WifiDirect,
    Unknown,
}

#[cfg(target_os = "windows")]
impl AdapterKind {
    pub fn default_priority(self) -> i32 {
        match self {
            Self::Ethernet => 100,
            Self::EthernetDirect => 80,
            Self::WifiDirect => 85,
            Self::Wifi => 60,
            Self::Unknown => 30,
        }
    }
}

#[cfg(target_os = "windows")]
pub fn infer_adapter_kind(label: &str) -> AdapterKind {
    let normalized = label.to_ascii_lowercase();

    if normalized.contains("wi-fi direct")
        || normalized.contains("wifi direct")
        || normalized.contains("wifi-direct")
    {
        return AdapterKind::WifiDirect;
    }

    if normalized.contains("ethernet") && normalized.contains("169.254") {
        return AdapterKind::EthernetDirect;
    }

    if normalized.contains("ethernet") {
        return AdapterKind::Ethernet;
    }

    if normalized.contains("wi-fi") || normalized.contains("wifi") || normalized.contains("wlan") {
        return AdapterKind::Wifi;
    }

    AdapterKind::Unknown
}

#[cfg(target_os = "windows")]
pub fn enumerate_local_adapters() -> Vec<LocalAdapter> {
    use std::net::IpAddr;

    use ipconfig::{IfType, OperStatus};

    let Ok(adapters) = ipconfig::get_adapters() else {
        return Vec::new();
    };

    let mut items = adapters
        .into_iter()
        .map(|adapter| {
            let friendly_name = adapter.friendly_name().to_string();
            let description = adapter.description().to_string();
            let label = format!("{friendly_name} {description}");

            let ipv4_addresses = adapter
                .ip_addresses()
                .iter()
                .filter_map(|ip| match ip {
                    IpAddr::V4(ipv4) => Some(ipv4.to_string()),
                    IpAddr::V6(_) => None,
                })
                .collect::<Vec<_>>();

            let gateway_addresses = adapter
                .gateways()
                .iter()
                .filter_map(|ip| match ip {
                    IpAddr::V4(ipv4) => Some(ipv4.to_string()),
                    IpAddr::V6(_) => None,
                })
                .collect::<Vec<_>>();

            let dns_servers = adapter
                .dns_servers()
                .iter()
                .filter_map(|ip| match ip {
                    IpAddr::V4(ipv4) => Some(ipv4.to_string()),
                    IpAddr::V6(_) => None,
                })
                .collect::<Vec<_>>();

            let has_private_ipv4 = adapter.ip_addresses().iter().any(|ip| match ip {
                IpAddr::V4(ipv4) => is_private_ipv4(*ipv4),
                IpAddr::V6(_) => false,
            });

            let has_link_local_ipv4 = adapter.ip_addresses().iter().any(|ip| match ip {
                IpAddr::V4(ipv4) => ipv4.is_link_local(),
                IpAddr::V6(_) => false,
            });

            let is_up = adapter.oper_status() == OperStatus::IfOperStatusUp;
            let is_virtual_like = is_virtual_label(&label)
                || matches!(
                    adapter.if_type(),
                    IfType::Tunnel | IfType::Ppp | IfType::SoftwareLoopback
                );

            let adapter_kind = classify_adapter_kind(
                adapter.if_type(),
                &label,
                has_private_ipv4,
                has_link_local_ipv4,
            );

            let mut priority = adapter_kind.default_priority();
            if is_up {
                priority += 20;
            } else {
                priority -= 40;
            }

            if has_private_ipv4 {
                priority += 20;
            }

            if has_link_local_ipv4 {
                priority += 5;
            }

            priority -= (adapter.ipv4_metric().min(1000) / 10) as i32;

            if is_virtual_like {
                priority -= 120;
            }

            if ipv4_addresses.is_empty() {
                priority -= 80;
            }

            let (status, status_reason) = if is_virtual_like {
                (
                    LocalAdapterStatus::Ignored,
                    Some("virtual or VPN-like interface".to_string()),
                )
            } else if !is_up {
                (
                    LocalAdapterStatus::Ignored,
                    Some("adapter is not operational".to_string()),
                )
            } else if ipv4_addresses.is_empty() {
                (
                    LocalAdapterStatus::Ignored,
                    Some("no IPv4 address assigned".to_string()),
                )
            } else if !has_private_ipv4 && !has_link_local_ipv4 {
                (
                    LocalAdapterStatus::Ignored,
                    Some("IPv4 address is not on a LAN/private range".to_string()),
                )
            } else {
                (LocalAdapterStatus::Active, None)
            };

            LocalAdapter {
                id: adapter.adapter_name().to_string(),
                friendly_name,
                description,
                adapter_kind,
                if_type: format!("{:?}", adapter.if_type()),
                status,
                status_reason,
                priority,
                is_preferred: false,
                ipv4_addresses,
                gateway_addresses,
                dns_servers,
                physical_address: adapter.physical_address().map(format_physical_address),
                receive_link_speed_mbps: bits_to_mbps(adapter.receive_link_speed()),
                transmit_link_speed_mbps: bits_to_mbps(adapter.transmit_link_speed()),
                ipv4_metric: adapter.ipv4_metric(),
            }
        })
        .collect::<Vec<_>>();

    items.retain(|adapter| !is_loopback_adapter(adapter));

    let preferred_priority = items
        .iter()
        .filter(|adapter| adapter.status == LocalAdapterStatus::Active)
        .map(|adapter| adapter.priority)
        .max();

    if let Some(priority) = preferred_priority {
        for adapter in &mut items {
            adapter.is_preferred =
                adapter.status == LocalAdapterStatus::Active && adapter.priority == priority;
        }
    }

    items.sort_by(|left, right| {
        right
            .priority
            .cmp(&left.priority)
            .then_with(|| left.friendly_name.cmp(&right.friendly_name))
    });

    items
}

#[cfg(not(target_os = "windows"))]
pub fn enumerate_local_adapters() -> Vec<LocalAdapter> {
    vec![LocalAdapter {
        id: "manual-ip".to_string(),
        friendly_name: "Manual IP connection".to_string(),
        description: "Automatic adapter detection is not available on this platform yet."
            .to_string(),
        adapter_kind: AdapterKind::Unknown,
        if_type: "manual".to_string(),
        status: LocalAdapterStatus::Ignored,
        status_reason: Some(
            "Use manual IP until Android network discovery is implemented.".to_string(),
        ),
        priority: 0,
        is_preferred: false,
        ipv4_addresses: Vec::new(),
        gateway_addresses: Vec::new(),
        dns_servers: Vec::new(),
        physical_address: None,
        receive_link_speed_mbps: None,
        transmit_link_speed_mbps: None,
        ipv4_metric: 0,
    }]
}

#[derive(Debug, Clone)]
pub struct DiscoveryEndpoint {
    pub adapter_id: String,
    pub bind_ip: Ipv4Addr,
    pub broadcast_ip: Ipv4Addr,
    pub priority: i32,
}

#[cfg(target_os = "windows")]
pub fn enumerate_discovery_endpoints() -> Vec<DiscoveryEndpoint> {
    use std::net::IpAddr;

    use ipconfig::{IfType, OperStatus};

    let Ok(adapters) = ipconfig::get_adapters() else {
        return Vec::new();
    };

    let mut endpoints = Vec::new();

    for adapter in adapters {
        let friendly_name = adapter.friendly_name().to_string();
        let description = adapter.description().to_string();
        let label = format!("{friendly_name} {description}");
        let is_up = adapter.oper_status() == OperStatus::IfOperStatusUp;
        let is_virtual_like = is_virtual_label(&label)
            || matches!(
                adapter.if_type(),
                IfType::Tunnel | IfType::Ppp | IfType::SoftwareLoopback
            );

        if !is_up || is_virtual_like {
            continue;
        }

        for ip in adapter.ip_addresses() {
            let IpAddr::V4(ipv4) = ip else {
                continue;
            };

            if !is_private_ipv4(*ipv4) && !ipv4.is_link_local() {
                continue;
            }

            let detected_prefix_len = adapter
                .prefixes()
                .iter()
                .filter_map(|(network, prefix_len)| match network {
                    IpAddr::V4(network_v4)
                        if network_address(*ipv4, *prefix_len) == *network_v4 =>
                    {
                        Some(*prefix_len)
                    }
                    _ => None,
                })
                .max()
                .unwrap_or(24);

            let has_private_ipv4 = is_private_ipv4(*ipv4);
            let has_link_local_ipv4 = ipv4.is_link_local();
            let prefix_len = if has_link_local_ipv4 && !has_private_ipv4 {
                16
            } else {
                detected_prefix_len
            };
            let adapter_kind = classify_adapter_kind(
                adapter.if_type(),
                &label,
                has_private_ipv4,
                has_link_local_ipv4,
            );

            let mut priority = adapter_kind.default_priority() + 20;
            if has_private_ipv4 {
                priority += 20;
            }
            if has_link_local_ipv4 {
                priority += 5;
            }
            priority -= (adapter.ipv4_metric().min(1000) / 10) as i32;

            endpoints.push(DiscoveryEndpoint {
                adapter_id: adapter.adapter_name().to_string(),
                bind_ip: *ipv4,
                broadcast_ip: broadcast_address(*ipv4, prefix_len),
                priority,
            });

            endpoints.push(DiscoveryEndpoint {
                adapter_id: adapter.adapter_name().to_string(),
                bind_ip: *ipv4,
                broadcast_ip: Ipv4Addr::new(255, 255, 255, 255),
                priority: priority + 2,
            });

            if has_link_local_ipv4 {
                endpoints.push(DiscoveryEndpoint {
                    adapter_id: adapter.adapter_name().to_string(),
                    bind_ip: *ipv4,
                    broadcast_ip: Ipv4Addr::new(169, 254, 255, 255),
                    priority: priority + 4,
                });
            }

            for gateway in adapter.gateways().iter().filter_map(|ip| match ip {
                IpAddr::V4(gateway) => Some(*gateway),
                IpAddr::V6(_) => None,
            }) {
                if gateway != *ipv4 && (is_private_ipv4(gateway) || gateway.is_link_local()) {
                    endpoints.push(DiscoveryEndpoint {
                        adapter_id: adapter.adapter_name().to_string(),
                        bind_ip: *ipv4,
                        broadcast_ip: gateway,
                        priority: priority + 3,
                    });
                }
            }

            for target in likely_subnet_hosts(*ipv4, prefix_len).into_iter() {
                endpoints.push(DiscoveryEndpoint {
                    adapter_id: adapter.adapter_name().to_string(),
                    bind_ip: *ipv4,
                    broadcast_ip: target,
                    priority: priority - 5,
                });
            }
        }
    }

    endpoints.sort_by(|left, right| {
        right
            .priority
            .cmp(&left.priority)
            .then_with(|| left.bind_ip.octets().cmp(&right.bind_ip.octets()))
    });

    endpoints
}

#[cfg(not(target_os = "windows"))]
pub fn enumerate_discovery_endpoints() -> Vec<DiscoveryEndpoint> {
    vec![DiscoveryEndpoint {
        adapter_id: "default".to_string(),
        bind_ip: Ipv4Addr::UNSPECIFIED,
        broadcast_ip: Ipv4Addr::new(255, 255, 255, 255),
        priority: 0,
    }]
}

#[cfg(target_os = "windows")]
fn classify_adapter_kind(
    if_type: ipconfig::IfType,
    label: &str,
    has_private_ipv4: bool,
    has_link_local_ipv4: bool,
) -> AdapterKind {
    use ipconfig::IfType;

    match if_type {
        IfType::EthernetCsmacd => {
            if !has_private_ipv4 && has_link_local_ipv4 {
                AdapterKind::EthernetDirect
            } else {
                AdapterKind::Ethernet
            }
        }
        IfType::Ieee80211 => {
            let normalized = label.to_ascii_lowercase();
            if normalized.contains("wi-fi direct")
                || normalized.contains("wifi direct")
                || normalized.contains("wifi-direct")
            {
                AdapterKind::WifiDirect
            } else {
                AdapterKind::Wifi
            }
        }
        _ => {
            let inferred = infer_adapter_kind(label);
            if inferred == AdapterKind::Unknown && !has_private_ipv4 && has_link_local_ipv4 {
                AdapterKind::EthernetDirect
            } else {
                inferred
            }
        }
    }
}

#[cfg(target_os = "windows")]
fn bits_to_mbps(bits_per_second: u64) -> Option<u64> {
    if bits_per_second == 0 {
        None
    } else {
        Some(bits_per_second / 1_000_000)
    }
}

#[cfg(target_os = "windows")]
fn format_physical_address(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|value| format!("{value:02X}"))
        .collect::<Vec<_>>()
        .join("-")
}

#[cfg(target_os = "windows")]
fn is_virtual_label(label: &str) -> bool {
    let normalized = label.to_ascii_lowercase();
    // Windows exposes the real direct-radio link as a virtual adapter.
    if normalized.contains("wi-fi direct") || normalized.contains("wifi direct") {
        return false;
    }
    let markers = [
        "virtual",
        "vmware",
        "virtualbox",
        "hyper-v",
        "wireguard",
        "tailscale",
        "zerotier",
        "wintun",
        "vpn",
        "loopback",
        "docker",
        "bluetooth",
        "npcap",
        "tap-",
    ];

    markers.iter().any(|marker| normalized.contains(marker))
}

#[cfg(all(test, target_os = "windows"))]
mod direct_wifi_tests {
    use super::*;

    #[test]
    fn direct_wifi_adapter_is_eligible_without_enabling_other_virtual_adapters() {
        assert!(!is_virtual_label("Microsoft Wi-Fi Direct Virtual Adapter #2"));
        assert!(is_virtual_label("Hyper-V Virtual Ethernet Adapter"));
        assert!(is_virtual_label("WireGuard Tunnel"));
    }
}

#[cfg(target_os = "windows")]
fn is_loopback_adapter(adapter: &LocalAdapter) -> bool {
    let label = format!("{} {}", adapter.friendly_name, adapter.description).to_ascii_lowercase();
    label.contains("loopback")
        || adapter.if_type.eq_ignore_ascii_case("softwareloopback")
        || (!adapter.ipv4_addresses.is_empty()
            && adapter
                .ipv4_addresses
                .iter()
                .all(|address| address.starts_with("127.")))
}

#[cfg(target_os = "windows")]
fn is_private_ipv4(address: Ipv4Addr) -> bool {
    address.is_private()
}

#[cfg(target_os = "windows")]
fn network_address(address: Ipv4Addr, prefix_len: u32) -> Ipv4Addr {
    let mask = subnet_mask(prefix_len);
    Ipv4Addr::from(u32::from(address) & mask)
}

#[cfg(target_os = "windows")]
fn broadcast_address(address: Ipv4Addr, prefix_len: u32) -> Ipv4Addr {
    let mask = subnet_mask(prefix_len);
    Ipv4Addr::from((u32::from(address) & mask) | !mask)
}

#[cfg(target_os = "windows")]
fn likely_subnet_hosts(address: Ipv4Addr, prefix_len: u32) -> Vec<Ipv4Addr> {
    if prefix_len > 30 {
        return Vec::new();
    }

    let mask = subnet_mask(prefix_len);
    let network = u32::from(address) & mask;
    let broadcast = u32::from(broadcast_address(address, prefix_len));
    let own = u32::from(address);
    let mut targets = Vec::new();

    for host in 1..=20u32 {
        let candidate = network.saturating_add(host);
        if candidate != own && candidate < broadcast {
            targets.push(Ipv4Addr::from(candidate));
        }
    }

    let gateway_like = network.saturating_add(254);
    if gateway_like != own && gateway_like < broadcast {
        targets.push(Ipv4Addr::from(gateway_like));
    }

    targets
}

#[cfg(target_os = "windows")]
fn subnet_mask(prefix_len: u32) -> u32 {
    if prefix_len == 0 {
        0
    } else {
        u32::MAX << (32 - prefix_len.min(32))
    }
}
