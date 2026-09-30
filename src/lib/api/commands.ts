import { invoke } from "@tauri-apps/api/core";
import type {
  AppBootstrap,
  AppSettings,
  FirewallRuleResult,
  LocalAdapter,
  PeerDevice,
  PlatformCapabilities,
  RemoteAccessStatus,
  SendDraft,
  TransferSummary
} from "./types";

const mockLocalAdapters: LocalAdapter[] = [];

const mockBootstrap: AppBootstrap = {
  appName: "LanDrop",
  phaseLabel: "Ready",
  phaseCompleted: 0,
  phaseTotal: 0,
  launchedAtMs: Date.now(),
  settings: {
    deviceName: "This PC",
    deviceKind: "desktop",
    receiveEnabled: true,
    requirePairCode: false,
    pairCode: "482731",
    quickSaveMode: "off",
    trustedDeviceIds: [],
    trustedDevices: [],
    defaultReceiveFolder: null,
    adapterPriorityOrder: [],
    disabledAdapterIds: [],
    preferEthernet: true,
    allowWifi: true,
    allowApipa: true,
    concurrentFileStreams: 4,
    discoveryPort: 50500,
    tcpPort: 50501,
    remoteAccessEnabled: false,
    remoteAutoPortMapping: true,
    remoteListenPort: 50502,
    theme: "system"
  },
  localAdapters: mockLocalAdapters,
  peers: [],
  incomingOffer: null,
  sendDraft: null,
  transfers: [],
  remoteAccessStatus: {
    state: "disabled",
    message: "Remote access is disabled.",
    publicHost: null,
    externalPort: null,
    mappingMethod: null
  }
};

const mockPlatformCapabilities: PlatformCapabilities = {
  platform: "windows",
  canPickFolders: true,
  canDragDropFiles: true,
  canConfigureFirewall: true,
  supportsNetworkAdapterOrdering: true,
  supportsAndroidNsd: false,
  supportsNotifications: false
};

function isTauriRuntime() {
  return Boolean(
    typeof window !== "undefined" &&
      "__TAURI_INTERNALS__" in window &&
      (window as Window & { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
  );
}

export async function getAppBootstrap(): Promise<AppBootstrap> {
  if (!isTauriRuntime()) {
    return mockBootstrap;
  }

  return invoke<AppBootstrap>("get_app_bootstrap");
}

export async function getPlatformCapabilities(): Promise<PlatformCapabilities> {
  if (!isTauriRuntime()) {
    return mockPlatformCapabilities;
  }

  return invoke<PlatformCapabilities>("get_platform_capabilities");
}

export async function getDiagnosticsReport(): Promise<string> {
  if (!isTauriRuntime()) {
    return JSON.stringify(mockBootstrap, null, 2);
  }

  return invoke<string>("get_diagnostics_report");
}

export async function refreshDiscovery(): Promise<AppBootstrap> {
  if (!isTauriRuntime()) {
    return mockBootstrap;
  }

  return invoke<AppBootstrap>("refresh_discovery");
}

export async function setReceiveEnabled(enabled: boolean): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.receiveEnabled = enabled;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_receive_enabled", { enabled });
}

export async function setRemoteAccessEnabled(enabled: boolean): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.remoteAccessEnabled = enabled;
    mockBootstrap.remoteAccessStatus = enabled
      ? {
          state: "manual_required",
          message: "Mock router requires manual forwarding.",
          publicHost: null,
          externalPort: mockBootstrap.settings.remoteListenPort,
          mappingMethod: null
        }
      : {
          state: "disabled",
          message: "Remote access is disabled.",
          publicHost: null,
          externalPort: null,
          mappingMethod: null
        };
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_remote_access_enabled", { enabled });
}

export async function setRemoteAutoPortMapping(enabled: boolean): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.remoteAutoPortMapping = enabled;
    if (mockBootstrap.settings.remoteAccessEnabled) {
      mockBootstrap.remoteAccessStatus = {
        state: enabled ? "mapped" : "manual_required",
        message: enabled
          ? "Mock router mapping is ready."
          : "Mock router requires manual forwarding.",
        publicHost: enabled ? "203.0.113.20" : null,
        externalPort: mockBootstrap.settings.remoteListenPort,
        mappingMethod: enabled ? "upnp" : null
      };
    }
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_remote_auto_port_mapping", { enabled });
}

export async function createRemoteInvite(publicHost: string): Promise<string> {
  if (!isTauriRuntime()) {
    return `landrop://remote/mock-${publicHost}`;
  }

  return invoke<string>("create_remote_invite", { publicHost });
}

export async function createOverlayInvite(overlayEndpoint: string): Promise<string> {
  if (!isTauriRuntime()) {
    return `landrop://remote/mock-overlay-${overlayEndpoint}`;
  }

  return invoke<string>("create_overlay_invite", { overlayEndpoint });
}

export async function getRemoteAccessStatus(): Promise<RemoteAccessStatus> {
  if (!isTauriRuntime()) {
    return mockBootstrap.remoteAccessStatus;
  }

  return invoke<RemoteAccessStatus>("get_remote_access_status");
}

export async function refreshRemoteMapping(): Promise<RemoteAccessStatus> {
  if (!isTauriRuntime()) {
    mockBootstrap.remoteAccessStatus = {
      state: mockBootstrap.settings.remoteAccessEnabled ? "manual_required" : "disabled",
      message: mockBootstrap.settings.remoteAccessEnabled
        ? "Mock router requires manual forwarding."
        : "Remote access is disabled.",
      publicHost: null,
      externalPort: mockBootstrap.settings.remoteListenPort,
      mappingMethod: null
    };
    return mockBootstrap.remoteAccessStatus;
  }

  return invoke<RemoteAccessStatus>("refresh_remote_mapping");
}

export async function resetRemoteIdentity(): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.remoteAccessStatus = {
      state: "disabled",
      message: "Remote access is disabled.",
      publicHost: null,
      externalPort: null,
      mappingMethod: null
    };
    return;
  }

  return invoke<void>("reset_remote_identity");
}

export async function addRemotePeer(invite: string): Promise<PeerDevice> {
  if (!isTauriRuntime()) {
    throw new Error(`Remote invite requires the desktop app: ${invite}`);
  }

  return invoke<PeerDevice>("add_remote_peer", { invite });
}

export async function setRequirePairCode(enabled: boolean): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.requirePairCode = enabled;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_require_pair_code", { enabled });
}

export async function setQuickSaveMode(
  quickSaveMode: AppSettings["quickSaveMode"]
): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.quickSaveMode = quickSaveMode;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_quick_save_mode", { quickSaveMode });
}

export async function untrustDevice(deviceId: string): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.trustedDeviceIds = mockBootstrap.settings.trustedDeviceIds.filter(
      (id) => id !== deviceId
    );
    mockBootstrap.settings.trustedDevices = mockBootstrap.settings.trustedDevices.filter(
      (device) => device.deviceId !== deviceId
    );
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("untrust_device", { deviceId });
}

export async function clearTrustedDevices(): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.trustedDeviceIds = [];
    mockBootstrap.settings.trustedDevices = [];
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("clear_trusted_devices");
}

export async function setDeviceName(deviceName: string): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.deviceName = deviceName.trim();
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_device_name", { deviceName });
}

export async function setDeviceKind(deviceKind: string): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.deviceKind = deviceKind as AppSettings["deviceKind"];
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_device_kind", { deviceKind });
}

export async function setThemePreference(
  theme: AppSettings["theme"]
): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.theme = theme;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_theme_preference", { theme });
}

export async function setConcurrentFileStreams(count: number): Promise<AppSettings> {
  const normalized = Math.max(1, Math.min(100, Math.round(count)));
  if (!isTauriRuntime()) {
    mockBootstrap.settings.concurrentFileStreams = normalized;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_concurrent_file_streams", { count: normalized });
}

export async function pickDefaultReceiveFolder(): Promise<AppSettings | null> {
  if (!isTauriRuntime()) {
    return null;
  }

  return invoke<AppSettings | null>("pick_default_receive_folder");
}

export async function setDefaultReceiveFolder(
  folder: string | null
): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.defaultReceiveFolder = folder;
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_default_receive_folder", { folder });
}

export async function setAdapterPriorityOrder(
  adapterIds: string[]
): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.adapterPriorityOrder = adapterIds;
    const byId = new Map(adapterIds.map((id, index) => [id, index]));
    mockBootstrap.localAdapters = [...mockBootstrap.localAdapters].sort(
      (left, right) =>
        (byId.get(left.id) ?? 999) - (byId.get(right.id) ?? 999)
    );
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_adapter_priority_order", { adapterIds });
}

export async function setDisabledAdapterIds(
  adapterIds: string[]
): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.disabledAdapterIds = adapterIds;
    mockBootstrap.localAdapters = mockBootstrap.localAdapters.map((adapter) => ({
      ...adapter,
      status: adapterIds.includes(adapter.id) ? "ignored" : "active",
      statusReason: adapterIds.includes(adapter.id)
        ? "disabled in LanDrop settings"
        : adapter.statusReason
    }));
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("set_disabled_adapter_ids", { adapterIds });
}

export async function configureDirectLan(
  adapterId: string,
  role: "pc1" | "pc2"
): Promise<FirewallRuleResult> {
  if (!isTauriRuntime()) {
    return {
      success: true,
      message:
        role === "pc1"
          ? "Mock: direct cable IP set to 10.77.77.1."
          : "Mock: direct cable IP set to 10.77.77.2.",
      commands: []
    };
  }

  return invoke<FirewallRuleResult>("configure_direct_lan", { adapterId, role });
}

export async function revertDirectLan(
  adapterId: string
): Promise<FirewallRuleResult> {
  if (!isTauriRuntime()) {
    return {
      success: true,
      message: "Mock: adapter reverted to DHCP.",
      commands: []
    };
  }

  return invoke<FirewallRuleResult>("revert_direct_lan", { adapterId });
}

export async function regeneratePairCode(): Promise<AppSettings> {
  if (!isTauriRuntime()) {
    mockBootstrap.settings.pairCode = `${Math.floor(Math.random() * 1_000_000)}`.padStart(
      6,
      "0"
    );
    return mockBootstrap.settings;
  }

  return invoke<AppSettings>("regenerate_pair_code");
}

export async function pickSendFiles(): Promise<SendDraft | null> {
  if (!isTauriRuntime()) {
    return null;
  }

  return invoke<SendDraft | null>("pick_send_files");
}

export async function pickSendFolder(): Promise<SendDraft | null> {
  if (!isTauriRuntime()) {
    return null;
  }

  return invoke<SendDraft | null>("pick_send_folder");
}

export async function stageDroppedPaths(paths: string[]): Promise<SendDraft | null> {
  if (!isTauriRuntime()) {
    return null;
  }

  return invoke<SendDraft | null>("stage_dropped_paths", { paths });
}

export async function clearSendDraft(): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.sendDraft = null;
    return;
  }

  return invoke<void>("clear_send_draft");
}

export async function pickReceiveFolder(): Promise<string | null> {
  if (!isTauriRuntime()) {
    return mockBootstrap.settings.defaultReceiveFolder;
  }

  return invoke<string | null>("pick_receive_folder");
}

export async function sendStagedOffer(
  targetDeviceId: string,
  pairCode: string | null = null
): Promise<TransferSummary> {
  if (!isTauriRuntime()) {
    const target = mockBootstrap.peers.find((peer) => peer.deviceId === targetDeviceId);
    const draft = mockBootstrap.sendDraft;
    if (target?.requiresPairCode && pairCode !== mockBootstrap.settings.pairCode) {
      throw new Error("Pair code required or invalid.");
    }
    const summary: TransferSummary = {
      transferId: `local-${Date.now()}`,
      peerName: target?.name ?? "Unknown peer",
      direction: "outgoing",
      status: "awaiting_accept",
      fileCount: draft?.fileCount ?? 0,
      totalBytes: draft?.totalBytes ?? 0,
      completedBytes: 0,
      progressPct: 0,
      currentFile: "Waiting for receiver approval",
      speedMbps: null,
      etaSeconds: null
    };
    mockBootstrap.transfers = [summary, ...mockBootstrap.transfers];
    return summary;
  }

  return invoke<TransferSummary>("send_staged_offer", { targetDeviceId, pairCode });
}

export async function respondToIncomingOffer(
  transferId: string,
  accept: boolean,
  savePath: string | null = null
): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.incomingOffer = null;
    mockBootstrap.transfers = mockBootstrap.transfers.map((transfer) =>
      transfer.transferId === transferId
        ? {
            ...transfer,
            status: accept ? "accepted" : "rejected",
            currentFile: accept
              ? `Ready to save into ${savePath ?? "selected folder"}`
              : "Receiver rejected the transfer."
          }
        : transfer
    );
    return;
  }

  return invoke<void>("respond_to_incoming_offer", { transferId, accept, savePath });
}

export async function addManualPeer(
  ip: string,
  port: number | null = null
): Promise<PeerDevice> {
  if (!isTauriRuntime()) {
    const peer = {
      deviceId: `manual-${ip}`,
      instanceId: `manual-${Date.now()}`,
      name: `Manual ${ip}`,
      deviceKind: "desktop" as const,
      ip,
      port: port ?? 50501,
      adapterKind: "unknown" as const,
      availability: "available" as const,
      requiresPairCode: false,
      lastSeenMs: Date.now(),
      priority: 30
    };
    mockBootstrap.peers = [peer, ...mockBootstrap.peers.filter((item) => item.ip !== ip)];
    return peer;
  }

  return invoke("add_manual_peer", { ip, port });
}

export async function cancelTransfer(transferId: string): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.transfers = mockBootstrap.transfers.map((transfer) =>
      transfer.transferId === transferId
        ? { ...transfer, status: "canceled", currentFile: "Transfer canceled by user." }
        : transfer
    );
    return;
  }

  return invoke<void>("cancel_transfer", { transferId });
}

export async function setTransferPaused(transferId: string, paused: boolean): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.transfers = mockBootstrap.transfers.map((transfer) =>
      transfer.transferId === transferId
        ? {
            ...transfer,
            status: paused ? "paused" : "transferring",
            currentFile: paused ? "Transfer paused." : "Transfer resumed."
          }
        : transfer
    );
    return;
  }

  return invoke<void>("set_transfer_paused", { transferId, paused });
}

export async function retryTransfer(transferId: string): Promise<TransferSummary> {
  if (!isTauriRuntime()) {
    const previous = mockBootstrap.transfers.find((transfer) => transfer.transferId === transferId);
    if (!previous) {
      throw new Error("No retry context exists for this transfer.");
    }
    const summary: TransferSummary = {
      ...previous,
      transferId: `retry-${Date.now()}`,
      status: "awaiting_accept",
      completedBytes: 0,
      progressPct: 0,
      currentFile: "Waiting for receiver approval",
      speedMbps: null,
      etaSeconds: null
    };
    mockBootstrap.transfers = [summary, ...mockBootstrap.transfers];
    return summary;
  }

  return invoke<TransferSummary>("retry_transfer", { transferId });
}

export async function clearTransferHistory(): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.transfers = mockBootstrap.transfers.filter(
      (transfer) =>
        !["completed", "failed", "rejected", "canceled"].includes(transfer.status)
    );
    return;
  }

  return invoke<void>("clear_transfer_history");
}

export async function deleteTransferHistoryItem(transferId: string): Promise<void> {
  if (!isTauriRuntime()) {
    mockBootstrap.transfers = mockBootstrap.transfers.filter(
      (transfer) => transfer.transferId !== transferId
    );
    return;
  }

  return invoke<void>("delete_transfer_history_item", { transferId });
}

export async function ensureFirewallRules(): Promise<FirewallRuleResult> {
  if (!isTauriRuntime()) {
    return {
      success: false,
      message: "Firewall setup is only available in the desktop app.",
      commands: []
    };
  }

  return invoke<FirewallRuleResult>("ensure_firewall_rules");
}

export async function removeFirewallRules(): Promise<FirewallRuleResult> {
  if (!isTauriRuntime()) {
    return {
      success: false,
      message: "Firewall removal is only available in the desktop app.",
      commands: []
    };
  }

  return invoke<FirewallRuleResult>("remove_firewall_rules");
}
