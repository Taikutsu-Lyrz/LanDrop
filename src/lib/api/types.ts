export type AdapterKind = "ethernet" | "ethernet_direct" | "wifi" | "wifi_direct" | "unknown";

export type Availability =
  | "available"
  | "busy"
  | "receive_disabled"
  | "transferring";

export type TransferDirection = "incoming" | "outgoing";

export type TransferStatus =
  | "idle"
  | "scanning"
  | "awaiting_accept"
  | "accepted"
  | "rejected"
  | "canceled"
  | "transferring"
  | "paused"
  | "completed"
  | "failed";

export type ThemePreference = "system" | "light" | "dark" | "oled";
export type QuickSaveMode = "off" | "favorites" | "on";
export type DeviceKind = "desktop" | "laptop" | "phone" | "tablet" | "android";

export type TrustedDevice = {
  deviceId: string;
  name: string;
  ip: string;
  lastSeenMs: number;
};

export type PeerDevice = {
  deviceId: string;
  instanceId: string;
  name: string;
  deviceKind: DeviceKind | string;
  ip: string;
  port: number;
  adapterKind: AdapterKind;
  availability: Availability;
  requiresPairCode: boolean;
  lastSeenMs: number;
  priority: number;
};

export type LocalAdapterStatus = "active" | "ignored";

export type LocalAdapter = {
  id: string;
  friendlyName: string;
  description: string;
  adapterKind: AdapterKind;
  ifType: string;
  status: LocalAdapterStatus;
  statusReason: string | null;
  priority: number;
  isPreferred: boolean;
  ipv4Addresses: string[];
  gatewayAddresses: string[];
  dnsServers: string[];
  physicalAddress: string | null;
  receiveLinkSpeedMbps: number | null;
  transmitLinkSpeedMbps: number | null;
  ipv4Metric: number;
};

export type TransferSummary = {
  transferId: string;
  peerName: string;
  direction: TransferDirection;
  status: TransferStatus;
  fileCount: number;
  totalBytes: number;
  completedBytes: number;
  progressPct: number;
  currentFile: string | null;
  speedMbps: number | null;
  etaSeconds: number | null;
};

export type TransferTopLevelItem = {
  kind: "file" | "directory";
  name: string;
  size?: number;
};

export type TransferManifestEntry = {
  relativePath: string;
  kind: "file" | "directory";
  size: number | null;
  modifiedAtMs: number | null;
  sourcePath?: string | null;
};

export type SendDraft = {
  rootCount: number;
  fileCount: number;
  directoryCount: number;
  totalBytes: number;
  containsFolders: boolean;
  topLevelItems: TransferTopLevelItem[];
  manifestEntries: TransferManifestEntry[];
  sourcePaths: string[];
  warnings: string[];
  scannedAtMs: number;
};

export type IncomingTransferOffer = {
  transferId: string;
  senderDeviceId: string;
  senderName: string;
  totalBytes: number;
  fileCount: number;
  directoryCount: number;
  requiresPairCode: boolean;
  topLevelItems: TransferTopLevelItem[];
};

export type AppSettings = {
  deviceName: string;
  deviceKind: DeviceKind;
  receiveEnabled: boolean;
  requirePairCode: boolean;
  pairCode: string;
  quickSaveMode: QuickSaveMode;
  trustedDeviceIds: string[];
  trustedDevices: TrustedDevice[];
  defaultReceiveFolder: string | null;
  adapterPriorityOrder: string[];
  disabledAdapterIds: string[];
  preferEthernet: boolean;
  allowWifi: boolean;
  allowApipa: boolean;
  concurrentFileStreams: number;
  discoveryPort: number;
  tcpPort: number;
  remoteAccessEnabled: boolean;
  remoteAutoPortMapping: boolean;
  remoteListenPort: number;
  theme: ThemePreference;
};

export type AppBootstrap = {
  appName: string;
  phaseLabel: string;
  phaseCompleted: number;
  phaseTotal: number;
  launchedAtMs: number;
  settings: AppSettings;
  localAdapters: LocalAdapter[];
  peers: PeerDevice[];
  incomingOffer: IncomingTransferOffer | null;
  sendDraft: SendDraft | null;
  transfers: TransferSummary[];
  remoteAccessStatus: RemoteAccessStatus;
};

export type RemoteAccessStatus = {
  state: "disabled" | "listening" | "mapped" | "manual_required" | "error";
  message: string;
  publicHost: string | null;
  externalPort: number | null;
  mappingMethod: string | null;
};

export type PlatformCapabilities = {
  platform: "windows" | "android" | "linux" | "macos" | "unknown";
  canPickFolders: boolean;
  canDragDropFiles: boolean;
  canConfigureFirewall: boolean;
  supportsNetworkAdapterOrdering: boolean;
  supportsAndroidNsd: boolean;
  supportsNotifications: boolean;
};

export type FirewallRuleResult = {
  success: boolean;
  message: string;
  commands: string[];
};
