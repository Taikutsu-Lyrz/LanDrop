import type { AdapterKind } from "../api/types";

export function formatBytes(value: number) {
  if (value === 0) {
    return "0 B";
  }

  const units = ["B", "KB", "MB", "GB", "TB"];
  const exponent = Math.min(
    Math.floor(Math.log(value) / Math.log(1024)),
    units.length - 1
  );
  const amount = value / 1024 ** exponent;
  return `${amount.toFixed(amount >= 100 || exponent === 0 ? 0 : 1)} ${units[exponent]}`;
}

export function formatSeconds(value: number) {
  if (value < 60) {
    return `${value}s remaining`;
  }

  const minutes = Math.floor(value / 60);
  const seconds = value % 60;
  return `${minutes}m ${seconds}s remaining`;
}

export function formatRelativeTime(timestampMs: number) {
  const deltaSeconds = Math.max(0, Math.floor((Date.now() - timestampMs) / 1000));
  if (deltaSeconds < 3) {
    return "just now";
  }
  if (deltaSeconds < 60) {
    return `${deltaSeconds}s ago`;
  }
  const minutes = Math.floor(deltaSeconds / 60);
  return `${minutes}m ago`;
}

export function formatAdapterKind(kind: AdapterKind) {
  const normalized = String(kind ?? "").toLowerCase().replace(/[-\s]/g, "_");
  switch (normalized) {
    case "ethernet":
      return "Ethernet";
    case "ethernet_direct":
      return "Ethernet direct";
    case "wifi":
    case "wlan":
      return "Wi-Fi";
    case "wifi_direct":
    case "wifi_p2p":
    case "p2p":
      return "Wi-Fi Direct";
    case "vpn":
    case "tailscale":
      return "VPN";
    default:
      return "Unknown link";
  }
}

export function formatMbps(value: number | null) {
  if (!value || value <= 0) {
    return "Unavailable";
  }

  if (value >= 1000) {
    return `${(value / 1000).toFixed(2)} Gbps`;
  }

  return `${value.toFixed(2)} Mbps`;
}

export function formatList(values: string[]) {
  if (values.length === 0) {
    return "None";
  }

  return values.join(", ");
}
