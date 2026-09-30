<svelte:head>
  <title>LanDrop</title>
  <meta
    name="description"
    content="Fast local file transfer for Windows, built with Tauri and Rust."
  />
</svelte:head>

<script lang="ts">
  import { onMount } from "svelte";
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import QRCode from "qrcode";
  import DeviceList from "./lib/components/DeviceList.svelte";
  import DirectWifi from "./lib/components/DirectWifi.svelte";
  import Icon from "./lib/components/Icon.svelte";
  import IncomingTransferDialog from "./lib/components/IncomingTransferDialog.svelte";
  import {
    addManualPeer,
    addRemotePeer,
    cancelTransfer,
    setTransferPaused,
    clearSendDraft,
    clearTransferHistory,
    clearTrustedDevices,
    configureDirectLan,
    createOverlayInvite,
    createRemoteInvite,
    deleteTransferHistoryItem,
    ensureFirewallRules,
    getAppBootstrap,
    getDiagnosticsReport,
    getPlatformCapabilities,
    getRemoteAccessStatus,
    pickDefaultReceiveFolder,
    pickReceiveFolder,
    pickSendFiles,
    pickSendFolder,
    refreshDiscovery,
    refreshRemoteMapping,
    removeFirewallRules,
    resetRemoteIdentity,
    regeneratePairCode,
    respondToIncomingOffer,
    retryTransfer,
    sendStagedOffer,
    setAdapterPriorityOrder,
    setDefaultReceiveFolder,
    setDeviceName,
    setDeviceKind,
    setConcurrentFileStreams,
    setQuickSaveMode,
    setRequirePairCode,
    setDisabledAdapterIds,
    revertDirectLan,
    stageDroppedPaths,
    setReceiveEnabled,
    setRemoteAccessEnabled,
    setRemoteAutoPortMapping,
    setThemePreference,
    untrustDevice
  } from "./lib/api/commands";
  import {
    listenDevicesUpdated,
    listenIncomingTransfer,
    listenSendDraftUpdated,
    listenSettingsUpdated,
    listenTransfersUpdated
  } from "./lib/api/events";
  import type {
    AppBootstrap,
    AppSettings,
    IncomingTransferOffer,
    LocalAdapter,
    PeerDevice,
    PlatformCapabilities,
    RemoteAccessStatus,
    SendDraft,
    TransferSummary
  } from "./lib/api/types";
  import { formatAdapterKind, formatBytes, formatMbps } from "./lib/utils/format";
  import {
    getInitialLanguage,
    initializeI18n,
    setAppLanguage,
    type AppLanguage
  } from "./lib/i18n";

  type ViewMode = "receive" | "send" | "settings";

  let bootstrap: AppBootstrap | null = null;
  let localAdapters: LocalAdapter[] = [];
  let devices: PeerDevice[] = [];
  let sendDraft: SendDraft | null = null;
  let transfers: TransferSummary[] = [];
  let settings: AppSettings | null = null;
  let selectedDeviceId: string | null = null;
  let incomingOffer: IncomingTransferOffer | null = null;
  let currentView: ViewMode = "send";
  let loading = true;
  let refreshing = false;
  let sendingOffer = false;
  let respondingToIncoming = false;
  let manualPeerBusy = false;
  let identityBusy = false;
  let pairCodeInput = "";
  let manualIp = "";
  let manualPort = "";
  let remotePublicHost = "";
  let remoteOverlayEndpoint = "";
  let remoteInviteMode: "direct" | "overlay" = "direct";
  let remoteInvite = "";
  let remoteInviteQr = "";
  let remoteInviteInput = "";
  let remoteBusy = false;
  let remoteAccessStatus: RemoteAccessStatus = {
    state: "disabled",
    message: "Remote access is disabled.",
    publicHost: null,
    externalPort: null,
    mappingMethod: null
  };
  let deviceNameDraft = "";
  let draggedAdapterId: string | null = null;
  let fileDropActive = false;
  let firewallBusy = false;
  let directLanBusyAdapterId: string | null = null;
  let diagnosticsBusy = false;
  let diagnosticsReport = "";
  let statusLine = "Starting LanDrop.";
  let platformCapabilities: PlatformCapabilities | null = null;
  let appLanguage: AppLanguage = getInitialLanguage();

  $: selectedDevice =
    devices.find((device) => device.deviceId === selectedDeviceId) ?? null;
  $: receiveEnabled = settings?.receiveEnabled ?? false;
  $: recentTransfers = transfers.slice(0, 6);
  $: stagedItems = sendDraft?.topLevelItems.slice(0, 6) ?? [];
  $: pairCodeDigits = pairCodeInput.trim();
  $: pairCodeInvalid =
    !!selectedDevice &&
    (selectedDevice.requiresPairCode
      ? pairCodeDigits.length !== 6
      : pairCodeDigits.length > 0 && pairCodeDigits.length !== 6);
  $: canPickFolders = platformCapabilities?.canPickFolders ?? true;
  $: canDragDropFiles = platformCapabilities?.canDragDropFiles ?? true;
  $: canConfigureFirewall = platformCapabilities?.canConfigureFirewall ?? true;
  $: supportsNetworkAdapterOrdering =
    platformCapabilities?.supportsNetworkAdapterOrdering ?? true;
  $: isMobileShell = platformCapabilities?.platform === "android";

  onMount(() => {
    let unlisteners: Array<() => void> = [];
    let refreshTimer: number | undefined;
    const stopI18n = initializeI18n(appLanguage);

    void initialize().then(() => {
      void handleRefreshDiscovery();
      refreshTimer = window.setInterval(() => {
        void handleRefreshDiscovery();
      }, 8000);
    });

    Promise.all([
      getCurrentWindow()
        .onDragDropEvent((event) => {
          if (event.payload.type === "over") {
            fileDropActive = true;
            currentView = "send";
          } else if (event.payload.type === "drop") {
            fileDropActive = false;
            currentView = "send";
            void handleDroppedPaths(event.payload.paths);
          } else {
            fileDropActive = false;
          }
        })
        .catch(() => () => undefined),
      listenDevicesUpdated((nextDevices) => {
        devices = nextDevices;
        if (!selectedDeviceId && nextDevices.length > 0) {
          selectedDeviceId = nextDevices[0].deviceId;
        }
        if (currentView === "send") {
          statusLine =
            nextDevices.length > 0
              ? `${nextDevices.length} nearby device${nextDevices.length === 1 ? "" : "s"} found.`
              : "Looking for nearby devices.";
        }
      }),
      listenSettingsUpdated((nextSettings) => {
        const receiveChanged =
          settings !== null && settings.receiveEnabled !== nextSettings.receiveEnabled;
        settings = nextSettings;
        deviceNameDraft = nextSettings.deviceName;
        if (receiveChanged) {
          statusLine = nextSettings.receiveEnabled
            ? "Ready to receive on this device."
            : "Receive is paused.";
        }
      }),
      listenIncomingTransfer((offer) => {
        incomingOffer = offer;
        if (offer) {
          currentView = "receive";
          statusLine = `${offer.senderName} wants to send ${offer.fileCount} files.`;
        }
      }),
      listenSendDraftUpdated((draft) => {
        sendDraft = draft;
        if (draft) {
          currentView = "send";
          statusLine = `${draft.fileCount} files staged and ready to send.`;
        } else {
          statusLine = "Send selection cleared.";
        }
      }),
      listenTransfersUpdated((nextTransfers) => {
        transfers = nextTransfers;
        const latestTransfer = nextTransfers[0];
        if (latestTransfer) {
          statusLine = describeTransfer(latestTransfer);
        }
      })
    ]).then((handlers) => {
      unlisteners = handlers;
    });

    return () => {
      if (refreshTimer) {
        window.clearInterval(refreshTimer);
      }
      for (const stop of unlisteners) {
        stop();
      }
      stopI18n();
    };
  });

  async function initialize() {
    loading = true;
    const [nextBootstrap, nextCapabilities] = await Promise.all([
      getAppBootstrap(),
      getPlatformCapabilities()
    ]);
    bootstrap = nextBootstrap;
    platformCapabilities = nextCapabilities;
    applyBootstrap(bootstrap);
    statusLine = bootstrap.incomingOffer
      ? `${bootstrap.incomingOffer.senderName} wants to send files.`
      : "Ready on your local network.";
    loading = false;
    if (bootstrap.settings.remoteAccessEnabled) {
      void restoreRemoteInviteIfNeeded();
    }
  }

  async function restoreRemoteInviteIfNeeded() {
    if (!settings?.remoteAccessEnabled || remoteBusy || remoteInvite) {
      return;
    }
    try {
      remoteAccessStatus = await getRemoteAccessStatus();
      if (remoteAccessStatus.publicHost) {
        remotePublicHost = remoteAccessStatus.publicHost;
      }
      if (remoteAccessStatus.state === "mapped") {
        await generateRemoteInvite(remotePublicHost);
      }
    } catch {
      // Invite can be created manually once a public address is available.
    }
  }

  async function handleRefreshDiscovery() {
    refreshing = true;
    bootstrap = await refreshDiscovery();
    applyBootstrap(bootstrap);
    refreshing = false;
  }

  async function handleReceiveToggle() {
    if (!settings) {
      return;
    }

    settings = await setReceiveEnabled(!settings.receiveEnabled);
  }

  async function handleRemoteAccessToggle() {
    if (!settings || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      const enabling = !settings.remoteAccessEnabled;
      settings = await setRemoteAccessEnabled(enabling);
      remoteAccessStatus = await getRemoteAccessStatus();
      if (remoteAccessStatus.publicHost) {
        remotePublicHost = remoteAccessStatus.publicHost;
      }
      if (enabling) {
        let firewallMessage = "";
        if (canConfigureFirewall) {
          firewallBusy = true;
          const firewallResult = await ensureFirewallRules();
          firewallMessage = firewallResult.success
            ? " Firewall access is ready."
            : ` ${firewallResult.message}`;
          firewallBusy = false;
        }
        if (remoteAccessStatus.state === "mapped" && remotePublicHost.trim()) {
          await generateRemoteInvite(remotePublicHost);
        } else if (remoteAccessStatus.state === "mapped") {
          try {
            await generateRemoteInvite("");
          } catch {
            // User can enter a public address and create the invite manually.
          }
        }
        statusLine = `${remoteAccessStatus.message}${firewallMessage}`;
        if (!settings.receiveEnabled) {
          statusLine = `${statusLine} Turn on Receive to accept remote files.`;
        }
      } else {
        remoteInvite = "";
        remoteInviteQr = "";
        remoteAccessStatus = {
          state: "disabled",
          message: "Remote access is disabled.",
          publicHost: null,
          externalPort: null,
          mappingMethod: null
        };
        if (canConfigureFirewall) {
          firewallBusy = true;
          try {
            await ensureFirewallRules();
          } catch {
            // Disabling still succeeds even if firewall refresh fails.
          }
          firewallBusy = false;
        }
        statusLine = "Remote access is disabled.";
      }
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change remote access.");
    } finally {
      firewallBusy = false;
      remoteBusy = false;
    }
  }

  async function handleRemoteAutoPortMappingToggle() {
    if (!settings || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      settings = await setRemoteAutoPortMapping(!settings.remoteAutoPortMapping);
      if (settings.remoteAccessEnabled) {
        remoteAccessStatus = await refreshRemoteMapping();
        if (remoteAccessStatus.publicHost) {
          remotePublicHost = remoteAccessStatus.publicHost;
        }
        if (remoteAccessStatus.state === "mapped" && remotePublicHost.trim()) {
          await generateRemoteInvite(remotePublicHost);
        }
      }
      statusLine = settings.remoteAutoPortMapping
        ? "Automatic router mapping enabled."
        : "Automatic router mapping disabled. Use manual forwarding.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change router mapping preference.");
    } finally {
      remoteBusy = false;
    }
  }

  async function handleRefreshRemoteMapping() {
    if (!settings?.remoteAccessEnabled || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      const previousHost = remotePublicHost;
      remoteAccessStatus = await refreshRemoteMapping();
      if (remoteAccessStatus.publicHost) {
        remotePublicHost = remoteAccessStatus.publicHost;
      }
      if (
        remoteAccessStatus.state === "mapped" &&
        remotePublicHost.trim() &&
        (!remoteInvite || remotePublicHost !== previousHost)
      ) {
        await generateRemoteInvite(remotePublicHost);
      }
      statusLine = remoteAccessStatus.message;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to check the router mapping.");
    } finally {
      remoteBusy = false;
    }
  }

  async function handleResetRemoteIdentity() {
    if (settings?.remoteAccessEnabled || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      await resetRemoteIdentity();
      remoteInvite = "";
      remoteInviteQr = "";
      remoteAccessStatus = {
        state: "disabled",
        message: "Remote identity reset. Previous invites are revoked.",
        publicHost: null,
        externalPort: null,
        mappingMethod: null
      };
      statusLine = remoteAccessStatus.message;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to reset remote identity.");
    } finally {
      remoteBusy = false;
    }
  }

  function selectRemoteInviteMode(mode: "direct" | "overlay") {
    remoteInviteMode = mode;
    remoteInvite = "";
    remoteInviteQr = "";
  }

  async function handleCreateRemoteInvite() {
    if (!settings?.remoteAccessEnabled || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      if (remoteInviteMode === "overlay") {
        await generateOverlayInvite(remoteOverlayEndpoint);
        statusLine = "Secure VPN/overlay invite created.";
      } else {
        await generateRemoteInvite(remotePublicHost);
        statusLine = "Secure remote invite created.";
      }
    } catch (error) {
      statusLine = unwrapError(error, "Unable to create remote invite.");
    } finally {
      remoteBusy = false;
    }
  }

  async function generateRemoteInvite(publicHost: string) {
    const invite = await createRemoteInvite(publicHost.trim());
    await setRemoteInvite(invite);
  }

  async function generateOverlayInvite(overlayEndpoint: string) {
    const invite = await createOverlayInvite(overlayEndpoint.trim());
    await setRemoteInvite(invite);
  }

  async function setRemoteInvite(invite: string) {
    const qr = await QRCode.toDataURL(invite, {
      errorCorrectionLevel: "L",
      margin: 4,
      width: 1200,
      color: {
        dark: "#000000ff",
        light: "#ffffffff"
      }
    });
    remoteInvite = invite;
    remoteInviteQr = qr;
  }

  async function handleCopyRemoteInvite() {
    if (!remoteInvite) {
      return;
    }
    try {
      await navigator.clipboard.writeText(remoteInvite);
      statusLine = "Secure remote invite copied.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to copy the remote invite.");
    }
  }

  async function handleAddRemotePeer() {
    if (!remoteInviteInput.trim() || remoteBusy) {
      return;
    }

    remoteBusy = true;
    try {
      const peer = await addRemotePeer(remoteInviteInput.trim());
      devices = [peer, ...devices.filter((device) => device.deviceId !== peer.deviceId)];
      selectedDeviceId = peer.deviceId;
      currentView = "send";
      statusLine = `${peer.name} connected through secure remote access.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to connect to the remote computer.");
    } finally {
      remoteBusy = false;
    }
  }

  async function handlePickFiles() {
    const draft = await pickSendFiles();
    if (draft) {
      sendDraft = draft;
      currentView = "send";
      statusLine = `${draft.fileCount} files selected.`;
    }
  }

  async function handlePickFolder() {
    if (!canPickFolders) {
      statusLine = "Folder picking is not available in this Android build yet.";
      return;
    }

    const draft = await pickSendFolder();
    if (draft) {
      sendDraft = draft;
      currentView = "send";
      statusLine = `${draft.fileCount} files found in the selected folder.`;
    }
  }

  async function handleClearDraft() {
    await clearSendDraft();
    sendDraft = null;
    pairCodeInput = "";
    statusLine = "Send selection cleared.";
  }

  async function handleDroppedPaths(paths: string[]) {
    if (!canDragDropFiles) {
      statusLine = "Drag and drop is only available on desktop.";
      return;
    }

    if (paths.length === 0) {
      return;
    }

    try {
      const draft = await stageDroppedPaths(paths);
      if (draft) {
        sendDraft = draft;
        statusLine = `${draft.fileCount} files staged from dropped items.`;
      }
    } catch (error) {
      statusLine = unwrapError(error, "Unable to stage dropped files.");
    }
  }

  async function handleSendOffer() {
    if (!selectedDevice || !sendDraft || sendingOffer) {
      return;
    }

    sendingOffer = true;

    try {
      const summary = await sendStagedOffer(
        selectedDevice.deviceId,
        pairCodeDigits.length === 6 ? pairCodeDigits : null
      );
      transfers = [
        summary,
        ...transfers.filter((transfer) => transfer.transferId !== summary.transferId)
      ];
      statusLine = `Waiting for ${summary.peerName} to accept.`;
    } catch (error) {
      const message = unwrapError(error, "Unable to send right now.");
      statusLine = message.toLowerCase().includes("pair code")
        ? "That receiver requires a 6-digit pair code. Enter it and send again."
        : message;
    } finally {
      sendingOffer = false;
    }
  }

  async function handleAcceptIncoming() {
    if (!incomingOffer) {
      return;
    }

    const offer = incomingOffer;
    respondingToIncoming = true;

    try {
      const savePath = settings?.defaultReceiveFolder ?? (await pickReceiveFolder());
      if (!savePath) {
        statusLine = "Receive location was not selected.";
        return;
      }

      await respondToIncomingOffer(offer.transferId, true, savePath);
      transfers = transfers.map((transfer) =>
        transfer.transferId === offer.transferId
          ? {
              ...transfer,
              status: "accepted",
              currentFile: `Saving into ${savePath}`
            }
          : transfer
      );
      incomingOffer = null;
      statusLine = `Receiving from ${offer.senderName}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to accept the transfer.");
    } finally {
      respondingToIncoming = false;
    }
  }

  async function handleRejectIncoming() {
    if (!incomingOffer) {
      return;
    }

    const offer = incomingOffer;
    respondingToIncoming = true;

    try {
      await respondToIncomingOffer(offer.transferId, false);
      transfers = transfers.map((transfer) =>
        transfer.transferId === offer.transferId
          ? {
              ...transfer,
              status: "rejected",
              currentFile: "Transfer rejected."
            }
          : transfer
      );
      incomingOffer = null;
      statusLine = `Rejected transfer from ${offer.senderName}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to reject the transfer.");
    } finally {
      respondingToIncoming = false;
    }
  }

  function selectDevice(deviceId: string) {
    selectedDeviceId = deviceId;
  }

  async function handleAddManualPeer() {
    const ip = manualIp.trim();
    if (!ip || manualPeerBusy) {
      return;
    }

    manualPeerBusy = true;
    try {
      const peer = await addManualPeer(ip, manualPort.trim() ? Number(manualPort.trim()) : null);
      devices = [peer, ...devices.filter((item) => item.deviceId !== peer.deviceId)];
      selectedDeviceId = peer.deviceId;
      currentView = "send";
      statusLine = `Added ${peer.name}.`;
      manualIp = "";
      manualPort = "";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to add that device.");
    } finally {
      manualPeerBusy = false;
    }
  }

  async function handleRequirePairCodeToggle() {
    if (!settings) {
      return;
    }

    settings = await setRequirePairCode(!settings.requirePairCode);
    statusLine = settings.requirePairCode
      ? `Pair code enabled: ${settings.pairCode}.`
      : "Pair code disabled.";
  }

  async function handleRegeneratePairCode() {
    settings = await regeneratePairCode();
    statusLine = `New pair code: ${settings.pairCode}.`;
  }

  async function handleSaveDeviceName() {
    const nextName = deviceNameDraft.trim();
    if (!nextName || !settings || identityBusy) {
      return;
    }

    identityBusy = true;
    try {
      settings = await setDeviceName(nextName);
      deviceNameDraft = settings.deviceName;
      statusLine = `Device name saved as ${settings.deviceName}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to save the device name.");
    } finally {
      identityBusy = false;
    }
  }

  function handleLanguageChange(language: AppLanguage) {
    appLanguage = language;
    setAppLanguage(language);
    statusLine = language === "prs-AF"
      ? "زبان LanDrop به دری تغییر کرد."
      : "LanDrop language changed to English.";
  }

  async function handleThemeChange(theme: AppSettings["theme"]) {
    if (!settings) {
      return;
    }

    try {
      settings = await setThemePreference(theme);
      statusLine = `Theme set to ${theme}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change the theme.");
    }
  }

  async function handleConcurrentFileStreamsChange(count: number) {
    if (!settings) {
      return;
    }

    try {
      settings = await setConcurrentFileStreams(count);
      statusLine = `Concurrent file streams set to ${settings.concurrentFileStreams}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change concurrent file streams.");
    }
  }

  async function handleDeviceKindChange(deviceKind: AppSettings["deviceKind"]) {
    if (!settings) {
      return;
    }

    try {
      settings = await setDeviceKind(deviceKind);
      statusLine = `Device type set to ${deviceKind}.`;
      void handleRefreshDiscovery();
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change the device type.");
    }
  }

  async function handleQuickSaveChange(quickSaveMode: AppSettings["quickSaveMode"]) {
    if (!settings) {
      return;
    }

    try {
      settings = await setQuickSaveMode(quickSaveMode);
      statusLine = `Quick Save set to ${quickSaveMode}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to change Quick Save.");
    }
  }

  async function handleUntrustDevice(deviceId: string) {
    try {
      settings = await untrustDevice(deviceId);
      statusLine = "Trusted device removed.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to remove trusted device.");
    }
  }

  async function handleClearTrustedDevices() {
    try {
      settings = await clearTrustedDevices();
      statusLine = "Trusted devices cleared.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to clear trusted devices.");
    }
  }

  async function handleLoadDiagnostics() {
    diagnosticsBusy = true;
    try {
      diagnosticsReport = await getDiagnosticsReport();
      statusLine = "Diagnostics report loaded.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to load diagnostics.");
    } finally {
      diagnosticsBusy = false;
    }
  }

  async function handleCancelTransfer(transferId: string) {
    try {
      await cancelTransfer(transferId);
      transfers = transfers.map((transfer) =>
        transfer.transferId === transferId
          ? {
              ...transfer,
              status: "canceled",
              currentFile: "Stopping transfer..."
            }
          : transfer
      );
      statusLine = "Stopping transfer.";
    } catch (error) {
      statusLine = unwrapError(error, "Unable to stop the transfer.");
    }
  }

  async function handleTransferPause(transferId: string, paused: boolean) {
    try {
      await setTransferPaused(transferId, paused);
      transfers = transfers.map((transfer) =>
        transfer.transferId === transferId
          ? {
              ...transfer,
              status: paused ? "paused" : "transferring",
              currentFile: paused ? "Transfer paused." : "Transfer resumed."
            }
          : transfer
      );
      statusLine = paused ? "Transfer paused." : "Transfer resumed.";
    } catch (error) {
      statusLine = unwrapError(error, paused ? "Unable to pause the transfer." : "Unable to resume the transfer.");
    }
  }

  async function handleRetryTransfer(transferId: string) {
    try {
      const summary = await retryTransfer(transferId);
      transfers = [
        summary,
        ...transfers.filter((transfer) => transfer.transferId !== summary.transferId)
      ];
      currentView = "send";
      statusLine = `Retry started for ${summary.peerName}.`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to retry the transfer.");
    }
  }

  async function handleClearHistory() {
    await clearTransferHistory();
    transfers = transfers.filter(
      (transfer) =>
        !["completed", "failed", "rejected", "canceled"].includes(transfer.status)
    );
    statusLine = "Transfer history cleared.";
  }

  async function handleDeleteTransfer(transferId: string) {
    await deleteTransferHistoryItem(transferId);
    transfers = transfers.filter((transfer) => transfer.transferId !== transferId);
    statusLine = "Transfer history item deleted.";
  }

  async function handlePickDefaultReceiveFolder() {
    if (!canPickFolders) {
      statusLine = "Default receive folder picking is not available in this Android build yet.";
      return;
    }

    const nextSettings = await pickDefaultReceiveFolder();
    if (!nextSettings) {
      statusLine = "Default receive folder was not changed.";
      return;
    }

    settings = nextSettings;
    statusLine = "Default receive folder updated.";
  }

  async function handleClearDefaultReceiveFolder() {
    settings = await setDefaultReceiveFolder(null);
    statusLine = "Default receive folder cleared.";
  }

  async function handleAdapterDrop(targetAdapterId: string) {
    if (!supportsNetworkAdapterOrdering) {
      draggedAdapterId = null;
      statusLine = "Network ordering is not available in this Android build yet.";
      return;
    }

    if (!draggedAdapterId || draggedAdapterId === targetAdapterId) {
      draggedAdapterId = null;
      return;
    }

    const nextAdapters = [...localAdapters];
    const fromIndex = nextAdapters.findIndex((adapter) => adapter.id === draggedAdapterId);
    const toIndex = nextAdapters.findIndex((adapter) => adapter.id === targetAdapterId);
    if (fromIndex < 0 || toIndex < 0) {
      draggedAdapterId = null;
      return;
    }

    const [moved] = nextAdapters.splice(fromIndex, 1);
    nextAdapters.splice(toIndex, 0, moved);
    localAdapters = nextAdapters;
    draggedAdapterId = null;

    settings = await setAdapterPriorityOrder(nextAdapters.map((adapter) => adapter.id));
    statusLine = `${moved.friendlyName} moved higher in the connection order.`;
  }

  function handleAdapterDragStart(event: DragEvent, adapterId: string) {
    if (!supportsNetworkAdapterOrdering) {
      return;
    }
    draggedAdapterId = adapterId;
    event.dataTransfer?.setData("text/plain", adapterId);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = "move";
      const target = event.currentTarget as HTMLElement | null;
      const row = target?.closest(".adapter-row") as HTMLElement | null;
      event.dataTransfer.setDragImage(row ?? target ?? document.body, 12, 20);
    }
  }

  function handleAdapterDragOver(event: DragEvent) {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = "move";
    }
  }

  async function handleAdapterKeyboardMove(event: KeyboardEvent, adapterId: string) {
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") {
      return;
    }
    event.preventDefault();
    const currentIndex = localAdapters.findIndex((adapter) => adapter.id === adapterId);
    const targetIndex = currentIndex + (event.key === "ArrowUp" ? -1 : 1);
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= localAdapters.length) {
      return;
    }
    draggedAdapterId = adapterId;
    await handleAdapterDrop(localAdapters[targetIndex].id);
  }

  async function handleAdapterEnabledToggle(adapter: LocalAdapter) {
    if (!settings) {
      return;
    }

    const disabled = new Set(settings.disabledAdapterIds);
    if (disabled.has(adapter.id)) {
      disabled.delete(adapter.id);
    } else {
      disabled.add(adapter.id);
    }

    settings = await setDisabledAdapterIds([...disabled]);
    bootstrap = await refreshDiscovery();
    applyBootstrap(bootstrap);
    statusLine = disabled.has(adapter.id)
      ? `${adapter.friendlyName} disabled for discovery.`
      : `${adapter.friendlyName} enabled for discovery.`;
  }

  async function handleConfigureDirectLan(adapter: LocalAdapter, role: "pc1" | "pc2") {
    directLanBusyAdapterId = adapter.id;
    try {
      const result = await configureDirectLan(adapter.id, role);
      statusLine = result.success
        ? result.message
        : `${result.message} Commands: ${result.commands.join(" && ")}`;
      bootstrap = await refreshDiscovery();
      applyBootstrap(bootstrap);
    } catch (error) {
      statusLine = unwrapError(error, "Unable to configure direct cable IP.");
    } finally {
      directLanBusyAdapterId = null;
    }
  }

  async function handleRevertDirectLan(adapter: LocalAdapter) {
    directLanBusyAdapterId = adapter.id;
    try {
      const result = await revertDirectLan(adapter.id);
      statusLine = result.success
        ? result.message
        : `${result.message} Commands: ${result.commands.join(" && ")}`;
      bootstrap = await refreshDiscovery();
      applyBootstrap(bootstrap);
    } catch (error) {
      statusLine = unwrapError(error, "Unable to revert direct cable IP.");
    } finally {
      directLanBusyAdapterId = null;
    }
  }

  async function handleEnsureFirewallRules() {
    if (!canConfigureFirewall) {
      statusLine = "Firewall setup is only needed on Windows.";
      return;
    }

    firewallBusy = true;
    try {
      const result = await ensureFirewallRules();
      statusLine = result.success
        ? result.message
        : `${result.message} Commands copied here for manual use: ${result.commands.join(" && ")}`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to configure firewall rules.");
    } finally {
      firewallBusy = false;
    }
  }

  async function handleRemoveFirewallRules() {
    if (!canConfigureFirewall) {
      statusLine = "Firewall removal is only needed on Windows.";
      return;
    }

    firewallBusy = true;
    try {
      const result = await removeFirewallRules();
      statusLine = result.success
        ? result.message
        : `${result.message} Commands: ${result.commands.join(" && ")}`;
    } catch (error) {
      statusLine = unwrapError(error, "Unable to remove LanDrop firewall rules.");
    } finally {
      firewallBusy = false;
    }
  }

  function applyBootstrap(nextBootstrap: AppBootstrap) {
    localAdapters = nextBootstrap.localAdapters;
    devices = nextBootstrap.peers;
    sendDraft = nextBootstrap.sendDraft;
    transfers = nextBootstrap.transfers;
    settings = nextBootstrap.settings;
    deviceNameDraft = nextBootstrap.settings.deviceName;
    incomingOffer = nextBootstrap.incomingOffer;
    const previousHost = remotePublicHost;
    remoteAccessStatus = nextBootstrap.remoteAccessStatus;
    if (nextBootstrap.remoteAccessStatus.publicHost) {
      remotePublicHost = nextBootstrap.remoteAccessStatus.publicHost;
    }

    const selectedStillExists = devices.some(
      (device) => device.deviceId === selectedDeviceId
    );
    if (!selectedStillExists) {
      selectedDeviceId = devices[0]?.deviceId ?? null;
    }

    if (
      nextBootstrap.settings.remoteAccessEnabled &&
      remotePublicHost.trim() &&
      remotePublicHost !== previousHost
    ) {
      void generateRemoteInvite(remotePublicHost).catch(() => undefined);
    }
  }

  function describeTransfer(transfer: TransferSummary) {
    switch (transfer.status) {
      case "awaiting_accept":
        return `Waiting for ${transfer.peerName} to accept.`;
      case "accepted":
        return `${transfer.peerName} accepted.`;
      case "transferring":
        return `Transferring with ${transfer.peerName}.`;
      case "paused":
        return `Transfer with ${transfer.peerName} is paused.`;
      case "completed":
        return `Finished with ${transfer.peerName}.`;
      case "rejected":
        return `${transfer.peerName} rejected the transfer.`;
      case "canceled":
        return `Transfer with ${transfer.peerName} was canceled.`;
      case "failed":
        return `Transfer with ${transfer.peerName} failed.`;
      default:
        return `${transfer.peerName}: ${transfer.status.replaceAll("_", " ")}.`;
    }
  }

  function transferCurrentFiles(transfer: TransferSummary) {
    return (transfer.currentFile ?? "Waiting")
      .split("\n")
      .map((file) => file.trim())
      .filter(Boolean);
  }

  function transferStatusLabel(status: TransferSummary["status"]) {
    switch (status) {
      case "awaiting_accept":
        return "Waiting";
      case "accepted":
        return "Accepted";
      case "rejected":
        return "Rejected";
      case "canceled":
        return "Canceled";
      case "transferring":
        return "Sending";
      case "paused":
        return "Paused";
      case "completed":
        return "Done";
      case "failed":
        return "Failed";
      default:
        return status.replaceAll("_", " ");
    }
  }

  function transferTone(status: TransferSummary["status"]) {
    if (status === "completed") {
      return "success";
    }
    if (status === "failed" || status === "rejected" || status === "canceled") {
      return "danger";
    }
    if (
      status === "awaiting_accept" ||
      status === "accepted" ||
      status === "transferring" ||
      status === "paused"
    ) {
      return "accent";
    }
    return "";
  }

  // Synced with Android formatTransferLine(): "45% • 12.3 MB / 27.1 MB • 8.50 Mbps"
  function formatTransferLine(transfer: TransferSummary) {
    const pct = Math.max(0, Math.min(100, Math.round(transfer.progressPct)));
    const size = `${formatBytes(transfer.completedBytes)} / ${formatBytes(transfer.totalBytes)}`;
    const speed = transfer.speedMbps ? formatMbps(transfer.speedMbps) : "0.00 Mbps";
    return `${pct}% • ${size} • ${speed}`;
  }

  function adapterEligibility(adapter: LocalAdapter) {
    if (adapter.status === "active") {
      return "Eligible";
    }

    return adapter.statusReason ?? "Not eligible";
  }

  function adapterIconName(adapter: LocalAdapter): "ethernet" | "wifi" | "adapter" {
    if (adapter.adapterKind === "wifi" || adapter.adapterKind === "wifi_direct") {
      return "wifi";
    }
    if (
      adapter.adapterKind === "ethernet" ||
      adapter.adapterKind === "ethernet_direct"
    ) {
      return "ethernet";
    }
    return "adapter";
  }

  function directLanEligible(adapter: LocalAdapter) {
    return (
      adapter.adapterKind === "ethernet" ||
      adapter.adapterKind === "ethernet_direct" ||
      adapter.adapterKind === "unknown"
    );
  }

  function directLanCommandsBusy(adapter: LocalAdapter) {
    return directLanBusyAdapterId === adapter.id;
  }

  function trustedDeviceRecords() {
    if (!settings) {
      return [];
    }
    const records = [...settings.trustedDevices];
    for (const deviceId of settings.trustedDeviceIds) {
      if (records.some((device) => device.deviceId === deviceId)) {
        continue;
      }
      const peer = devices.find((device) => device.deviceId === deviceId);
      records.push({
        deviceId,
        name: peer?.name ?? `Saved device ${deviceId.slice(0, 8)}`,
        ip: peer?.ip ?? "IP not recorded",
        lastSeenMs: peer?.lastSeenMs ?? 0
      });
    }
    return records;
  }

  function unwrapError(error: unknown, fallback: string) {
    if (error instanceof Error && error.message) {
      return error.message;
    }

    if (typeof error === "string" && error.trim().length > 0) {
      return error;
    }

    return fallback;
  }

  $: if (typeof document !== "undefined" && settings) {
    if (settings.theme === "system") {
      delete document.documentElement.dataset.theme;
    } else {
      document.documentElement.dataset.theme = settings.theme;
    }
  }
</script>

<div class:mobile-shell={isMobileShell} class="app-shell">
  <aside class="sidebar">
    <div class="brand-block">
      <div class="brand-mark">
        <img src="/linkdrop_minimal_icon.svg" alt="" />
      </div>
      <div>
        <div class="brand">LanDrop</div>
        <p class="sidebar-copy">Fast local transfer</p>
      </div>
    </div>

    <nav class="sidebar-nav">
      <button
        class:active={currentView === "receive"}
        class="nav-button"
        on:click={() => (currentView = "receive")}
      >
        <Icon name="receive" />
        <span>Receive</span>
      </button>
      <button
        class:active={currentView === "send"}
        class="nav-button"
        on:click={() => (currentView = "send")}
      >
        <Icon name="send" />
        <span>Send</span>
      </button>
      <button
        class:active={currentView === "settings"}
        class="nav-button"
        on:click={() => (currentView = "settings")}
      >
        <Icon name="sliders" />
        <span>Settings</span>
      </button>
    </nav>

    <div class="sidebar-footer">
      <span class={`mini-pill ${receiveEnabled ? "accent" : "warning"}`}>
        {receiveEnabled ? "Receive on" : "Receive paused"}
      </span>
      <div class="sidebar-device">{settings?.deviceName ?? "This PC"}</div>
      <p class="sidebar-status">{statusLine}</p>
    </div>
  </aside>

  <main class="main-shell">
    {#if currentView === "send"}
      <section class="view-shell">
        <header class="view-header">
          <div>
            <h1>Send</h1>
            <p>Choose files or a folder, then pick a nearby device.</p>
          </div>
          <button
            class="icon-button"
            aria-label="Refresh nearby devices"
            title="Refresh nearby devices"
            disabled={refreshing}
            on:click={handleRefreshDiscovery}
          >
            <Icon name="refresh" />
            <span>{refreshing ? "Refreshing" : "Refresh"}</span>
          </button>
        </header>

        <section class:file-drop-active={fileDropActive} class="selection-grid">
          <button class="selection-tile" on:click={handlePickFiles}>
            <Icon name="file" size={30} />
            <strong>File</strong>
            <span>Choose files</span>
          </button>
          {#if canPickFolders}
            <button class="selection-tile" on:click={handlePickFolder}>
              <Icon name="folder" size={30} />
              <strong>Folder</strong>
              <span>Choose a folder</span>
            </button>
          {/if}
          <button class="selection-tile" on:click={handleClearDraft} disabled={!sendDraft}>
            <Icon name="clear" size={30} />
            <strong>Clear</strong>
            <span>Reset selection</span>
          </button>
        </section>
        {#if fileDropActive}
          <div class="drop-banner">Drop files or folders anywhere to stage them for sending.</div>
        {/if}

        <section class="content-card">
          <div class="section-header">
            <div>
              <h2>Nearby devices</h2>
              <p>Devices on the same LAN appear here automatically.</p>
            </div>
          </div>
          <DeviceList
            {devices}
            activeDeviceId={selectedDeviceId}
            on:select={(event) => selectDevice(event.detail)}
          />
        </section>

        <section class="two-column">
          <section class="content-card">
            <div class="section-header">
              <div>
                <h2>Ready to send</h2>
                <p>
                  {#if selectedDevice}
                    Target: {selectedDevice.name}
                  {:else}
                    Pick a device first
                  {/if}
                </p>
              </div>
            </div>

            {#if loading}
              <div class="empty-state">Loading...</div>
            {:else if !sendDraft}
              <div class="empty-state">
                Nothing selected yet. Choose files{canPickFolders ? " or a folder" : ""} to start.
              </div>
            {:else}
              <div class="summary-stack">
                <div class="summary-row">
                  <span>{sendDraft.fileCount} files</span>
                  <span>{formatBytes(sendDraft.totalBytes)}</span>
                </div>
                <div class="summary-row">
                  <span>{sendDraft.directoryCount} folders</span>
                  <span>{sendDraft.rootCount} selected item{sendDraft.rootCount === 1 ? "" : "s"}</span>
                </div>

                {#if selectedDevice}
                  <label class="field-block">
                    <span>{selectedDevice.requiresPairCode ? "Pair code required" : "Pair code if required"}</span>
                    <input
                      class="text-input"
                      inputmode="numeric"
                      maxlength="6"
                      placeholder={selectedDevice.requiresPairCode ? "Enter receiver code" : "Optional 6 digits"}
                      value={pairCodeInput}
                      on:input={(event) =>
                        (pairCodeInput = event.currentTarget.value.replace(/\D/g, "").slice(0, 6))}
                    />
                    {#if pairCodeInvalid}
                      <span class="field-hint warning-text">Use exactly 6 digits.</span>
                    {/if}
                  </label>
                {/if}

                <div class="preview-list">
                  {#each stagedItems as item}
                    <div class="preview-row">
                      <span>{item.name}</span>
                      <span>
                        {item.kind}
                        {#if item.size}
                          | {formatBytes(item.size)}
                        {/if}
                      </span>
                    </div>
                  {/each}
                </div>

                {#if sendDraft.warnings.length > 0}
                  <div class="small-note">
                    {#each sendDraft.warnings as warning}
                      <div>{warning}</div>
                    {/each}
                  </div>
                {/if}

                <button
                  class="primary-button"
                  disabled={!selectedDevice || !sendDraft || sendingOffer || pairCodeInvalid}
                  on:click={handleSendOffer}
                >
                  {sendingOffer ? "Sending..." : selectedDevice ? `Send to ${selectedDevice.name}` : "Select a device"}
                </button>
              </div>
            {/if}
          </section>

          <section class="content-card">
            <div class="section-header">
              <div>
                <h2>Transfers</h2>
                <p>Recent activity on this device.</p>
              </div>
              {#if transfers.length > 0}
                <button class="soft-button" on:click={handleClearHistory}>Clear</button>
              {/if}
            </div>

            {#if recentTransfers.length === 0}
              <div class="empty-state">No transfers yet.</div>
            {:else}
              <div class="transfer-stack">
                {#each recentTransfers as transfer}
                  <article class="transfer-item">
                    <div class="transfer-line">
                      <div>
                        <strong>{transfer.peerName}</strong>
                        <div class="muted-line">
                          {transfer.direction === "outgoing" ? "Outgoing" : "Incoming"} | {transfer.fileCount} files | {formatBytes(transfer.totalBytes)}
                        </div>
                      </div>
                      <span class={`mini-pill ${transferTone(transfer.status)}`}>
                        {transferStatusLabel(transfer.status)}
                      </span>
                    </div>
                    <div class="progress-track">
                      <span style={`width: ${Math.max(0, Math.min(100, transfer.progressPct))}%`}></span>
                    </div>
                    <div class="muted-line transfer-foot">
                      <div class="transfer-files">
                        {#each transferCurrentFiles(transfer) as file}
                          <span title={file}>{file}</span>
                        {/each}
                      </div>
                      <span>
                        {formatTransferLine(transfer)}
                      </span>
                    </div>
                    <div class="inline-actions">
                      {#if transfer.status === "transferring"}
                        <button class="soft-button" on:click={() => handleTransferPause(transfer.transferId, true)}>
                          Pause
                        </button>
                        <button class="soft-button" on:click={() => handleCancelTransfer(transfer.transferId)}>
                          Cancel
                        </button>
                      {/if}
                      {#if transfer.status === "paused"}
                        <button class="soft-button" on:click={() => handleTransferPause(transfer.transferId, false)}>
                          Resume
                        </button>
                        <button class="soft-button" on:click={() => handleCancelTransfer(transfer.transferId)}>
                          Cancel
                        </button>
                      {/if}
                      {#if transfer.direction === "outgoing" && (transfer.status === "failed" || transfer.status === "canceled")}
                        <button class="soft-button" on:click={() => handleRetryTransfer(transfer.transferId)}>
                          Retry
                        </button>
                      {/if}
                      {#if transfer.status === "completed" || transfer.status === "failed" || transfer.status === "rejected" || transfer.status === "canceled"}
                        <button class="soft-button danger" on:click={() => handleDeleteTransfer(transfer.transferId)}>
                          Delete
                        </button>
                      {/if}
                    </div>
                  </article>
                {/each}
              </div>
            {/if}
          </section>
        </section>
      </section>
    {:else if currentView === "receive"}
      <section class="view-shell receive-shell">
        <header class="view-header">
          <div>
            <h1>Receive</h1>
            <p>Keep this screen open on the device that should receive files.</p>
          </div>
          <button class="soft-button" on:click={handleReceiveToggle}>
            {receiveEnabled ? "Pause receive" : "Enable receive"}
          </button>
        </header>

        <section class="receiver-stage">
          <div class="receiver-mark"><Icon name="receive" size={64} /></div>
          <h2>{settings?.deviceName ?? "This PC"}</h2>
          <p>
            {#if receiveEnabled}
              Ready to receive from nearby devices on this local network.
            {:else}
              Receiving is paused right now.
            {/if}
          </p>
          <div class="chip-row">
            <span class={`mini-pill ${receiveEnabled ? "accent" : "warning"}`}>
              {receiveEnabled ? "Receiving on" : "Paused"}
            </span>
            {#if settings?.requirePairCode}
              <span class="mini-pill accent">Code {settings.pairCode}</span>
            {/if}
            <span class="mini-pill">{devices.length} nearby</span>
          </div>
        </section>

        <section class="content-card">
          <div class="section-header">
            <div>
              <h2>Recent transfers</h2>
              <p>Completed, active, and failed transfers show up here.</p>
            </div>
          </div>

          {#if recentTransfers.length === 0}
            <div class="empty-state">No transfer activity yet.</div>
          {:else}
            <div class="transfer-stack">
              {#each recentTransfers as transfer}
                <article class="transfer-item">
                    <div class="transfer-line">
                      <div>
                        <strong>{transfer.peerName}</strong>
                        <div class="muted-line">
                          {transfer.direction === "outgoing" ? "Outgoing" : "Incoming"} | {transfer.fileCount} files | {formatBytes(transfer.totalBytes)}
                        </div>
                      </div>
                      <span class={`mini-pill ${transferTone(transfer.status)}`}>
                        {transferStatusLabel(transfer.status)}
                      </span>
                    </div>
                    <div class="progress-track">
                      <span style={`width: ${Math.max(0, Math.min(100, transfer.progressPct))}%`}></span>
                    </div>
                    <div class="muted-line transfer-foot">
                      <div class="transfer-files">
                        {#each transferCurrentFiles(transfer) as file}
                          <span title={file}>{file}</span>
                        {/each}
                      </div>
                      <span>
                        {formatTransferLine(transfer)}
                      </span>
                    </div>
                  <div class="inline-actions">
                    {#if transfer.status === "transferring"}
                      <button class="soft-button" on:click={() => handleTransferPause(transfer.transferId, true)}>
                        Pause
                      </button>
                      <button class="soft-button" on:click={() => handleCancelTransfer(transfer.transferId)}>
                        Cancel
                      </button>
                    {/if}
                    {#if transfer.status === "paused"}
                      <button class="soft-button" on:click={() => handleTransferPause(transfer.transferId, false)}>
                        Resume
                      </button>
                      <button class="soft-button" on:click={() => handleCancelTransfer(transfer.transferId)}>
                        Cancel
                      </button>
                    {/if}
                    {#if transfer.status === "completed" || transfer.status === "failed" || transfer.status === "rejected" || transfer.status === "canceled"}
                      <button class="soft-button danger" on:click={() => handleDeleteTransfer(transfer.transferId)}>
                        Delete
                      </button>
                    {/if}
                  </div>
                </article>
              {/each}
            </div>
          {/if}
        </section>
      </section>
    {:else}
      <section class="view-shell">
        <header class="view-header">
          <div>
            <h1>Settings</h1>
            <p>Device name, receive protection, remote file sharing, and network details.</p>
          </div>
        </header>

        <section class="settings-grid">
          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="device" /></div>
              <div>
                <h2>This device</h2>
                <p>How this PC appears to other LanDrop devices.</p>
              </div>
            </div>

            <label class="field-block">
              <span>Device name</span>
              <input
                class="text-input"
                maxlength="48"
                placeholder="Device name"
                value={deviceNameDraft}
                on:input={(event) => (deviceNameDraft = event.currentTarget.value)}
              />
            </label>

            <div class="inline-actions">
              <button
                class="primary-button"
                disabled={identityBusy || deviceNameDraft.trim().length === 0}
                on:click={handleSaveDeviceName}
              >
                {identityBusy ? "Saving..." : "Save name"}
              </button>
            </div>

            <div class="segment-control">
              <button
                class:active={settings?.deviceKind === "desktop"}
                class="segment-button"
                on:click={() => handleDeviceKindChange("desktop")}
              >
                Desktop
              </button>
              <button
                class:active={settings?.deviceKind === "laptop"}
                class="segment-button"
                on:click={() => handleDeviceKindChange("laptop")}
              >
                Laptop
              </button>
              <button
                class:active={settings?.deviceKind === "tablet"}
                class="segment-button"
                on:click={() => handleDeviceKindChange("tablet")}
              >
                Tablet
              </button>
              <button
                class:active={settings?.deviceKind === "phone"}
                class="segment-button"
                on:click={() => handleDeviceKindChange("phone")}
              >
                Phone
              </button>
            </div>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="sliders" /></div>
              <div>
                <h2>Appearance</h2>
                <p>Choose how LanDrop looks on this PC.</p>
              </div>
            </div>
            <div class="segment-control">
              <button
                class:active={settings?.theme === "system"}
                class="segment-button"
                on:click={() => handleThemeChange("system")}
              >
                System
              </button>
              <button
                class:active={settings?.theme === "light"}
                class="segment-button"
                on:click={() => handleThemeChange("light")}
              >
                Light
              </button>
              <button
                class:active={settings?.theme === "dark"}
                class="segment-button"
                on:click={() => handleThemeChange("dark")}
              >
                Dark
              </button>
              <button
                class:active={settings?.theme === "oled"}
                class="segment-button"
                on:click={() => handleThemeChange("oled")}
              >
                OLED
              </button>
            </div>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="sliders" /></div>
              <div>
                <h2>Language</h2>
                <p>Choose the language used by LanDrop.</p>
              </div>
            </div>
            <div class="segment-control language-control">
              <button
                class:active={appLanguage === "en"}
                class="segment-button"
                on:click={() => handleLanguageChange("en")}
              >
                English
              </button>
              <button
                class:active={appLanguage === "prs-AF"}
                class="segment-button"
                on:click={() => handleLanguageChange("prs-AF")}
              >
                دری
              </button>
            </div>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="sliders" /></div>
              <div>
                <h2>Transfer performance</h2>
                <p>Choose how many small files LanDrop may process in parallel when both devices support it.</p>
              </div>
            </div>

            <div class="summary-stack">
              <div class="summary-row">
                <span>Concurrent files</span>
                <span>{settings?.concurrentFileStreams ?? 4}</span>
              </div>
            </div>

            <label class="field-block">
              <span>Parallel stream limit</span>
              <input
                class="range-input"
                type="range"
                min="1"
                max="100"
                step="1"
                value={settings?.concurrentFileStreams ?? 4}
                on:change={(event) => handleConcurrentFileStreamsChange(Number(event.currentTarget.value))}
              />
            </label>

            <div class="inline-actions">
              {#each [1, 4, 8, 16, 32, 100] as streamCount}
                <button
                  class:active={settings?.concurrentFileStreams === streamCount}
                  class="segment-button compact-button"
                  on:click={() => handleConcurrentFileStreamsChange(streamCount)}
                >
                  {streamCount}
                </button>
              {/each}
            </div>

            <p class="small-note">
              Higher values help folders with thousands of small files, but very high values can hurt older disks or busy antivirus scans.
            </p>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="shield" /></div>
              <div>
                <h2>Receive protection</h2>
                <p>Control whether this device accepts incoming requests.</p>
              </div>
            </div>

            <div class="summary-stack">
              <div class="summary-row">
                <span>Receive</span>
                <span>{receiveEnabled ? "Enabled" : "Paused"}</span>
              </div>
              <div class="summary-row">
                <span>Pair code</span>
                <span>{settings?.requirePairCode ? settings.pairCode : "Off"}</span>
              </div>
            </div>

            <div class="inline-actions">
              <button class="soft-button" on:click={handleReceiveToggle}>
                {receiveEnabled ? "Pause receive" : "Enable receive"}
              </button>
              <button class="soft-button" on:click={handleRequirePairCodeToggle}>
                {settings?.requirePairCode ? "Disable code" : "Require code"}
              </button>
              <button class="soft-button" on:click={handleRegeneratePairCode}>
                New code
              </button>
            </div>

            <div class="segment-control">
              <button
                class:active={settings?.quickSaveMode === "off"}
                class="segment-button"
                on:click={() => handleQuickSaveChange("off")}
              >
                Ask every time
              </button>
              <button
                class:active={settings?.quickSaveMode === "favorites"}
                class="segment-button"
                on:click={() => handleQuickSaveChange("favorites")}
              >
                Favorites
              </button>
              <button
                class:active={settings?.quickSaveMode === "on"}
                class="segment-button"
                on:click={() => handleQuickSaveChange("on")}
              >
                Quick Save
              </button>
            </div>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="device" /></div>
              <div>
                <h2>Trusted devices</h2>
                <p>Devices approved for Favorites quick save.</p>
              </div>
            </div>
            <div class="summary-stack">
              <div class="summary-row">
                <span>Trusted devices</span>
                <span>{trustedDeviceRecords().length}</span>
              </div>
              {#if trustedDeviceRecords().length > 0}
                {#each trustedDeviceRecords() as device (device.deviceId)}
                  <div class="summary-row">
                    <span>
                      <strong>{device.name}</strong>
                      <small>{device.ip}</small>
                    </span>
                    <button class="soft-button danger compact-button" on:click={() => handleUntrustDevice(device.deviceId)}>
                      Remove
                    </button>
                  </div>
                {/each}
              {:else}
                <div class="empty-state">Accepted devices will appear here after a transfer.</div>
              {/if}
            </div>

            {#if trustedDeviceRecords().length > 0}
              <div class="inline-actions">
                <button class="soft-button danger" on:click={handleClearTrustedDevices}>
                  Clear trusted devices
                </button>
              </div>
            {/if}
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="folder" /></div>
              <div>
                <h2>Save location</h2>
                <p>Choose where accepted transfers should be saved by default.</p>
              </div>
            </div>

            <div class="summary-stack">
              <div class="summary-row">
                <span>Default folder</span>
                <span>{settings?.defaultReceiveFolder ?? "Ask every time"}</span>
              </div>
            </div>

            <div class="inline-actions">
              <button class="primary-button" on:click={handlePickDefaultReceiveFolder}>
                Choose folder
              </button>
              {#if settings?.defaultReceiveFolder}
                <button class="soft-button" on:click={handleClearDefaultReceiveFolder}>
                  Ask every time
                </button>
              {/if}
            </div>
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="plus" /></div>
              <div>
                <h2>Manual device</h2>
                <p>Add a device directly by IP if discovery misses it.</p>
              </div>
            </div>

            <div class="field-grid">
              <label class="field-block">
                <span>IP address</span>
                <input
                  class="text-input"
                  placeholder="192.168.1.20"
                  value={manualIp}
                  on:input={(event) => (manualIp = event.currentTarget.value)}
                />
              </label>
              <label class="field-block">
                <span>Port</span>
                <input
                  class="text-input"
                  placeholder="50501"
                  value={manualPort}
                  on:input={(event) => (manualPort = event.currentTarget.value)}
                />
              </label>
            </div>

            <div class="inline-actions">
              <button class="primary-button" disabled={manualPeerBusy} on:click={handleAddManualPeer}>
                {manualPeerBusy ? "Connecting..." : "Add device"}
              </button>
            </div>
          </section>

          {#if canConfigureFirewall}
            <section class="content-card">
              <div class="section-header">
                <div class="section-icon"><Icon name="shield" /></div>
                <div>
                  <h2>Windows firewall</h2>
                  <p>Install private-network allow rules for discovery and transfers.</p>
                </div>
              </div>

              <div class="summary-stack">
                <div class="summary-row">
                  <span>Discovery</span>
                  <span>UDP {settings?.discoveryPort ?? 50500}</span>
                </div>
                <div class="summary-row">
                  <span>Transfers</span>
                  <span>TCP {settings?.tcpPort ?? 50501}</span>
                </div>
                <div class="summary-row">
                  <span>Remote file sharing</span>
                  <span>TCP {settings?.remoteListenPort ?? 50502}</span>
                </div>
              </div>

              <div class="inline-actions">
                <button class="primary-button" disabled={firewallBusy} on:click={handleEnsureFirewallRules}>
                  {firewallBusy ? "Checking..." : "Set up firewall"}
                </button>
                <button
                  class="soft-button danger"
                  disabled={firewallBusy}
                  on:click={handleRemoveFirewallRules}
                >
                  Remove rules
                </button>
              </div>
            </section>
          {/if}

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="globe" /></div>
              <div>
                <h2>Remote file sharing</h2>
                <p>
                  Receive files over an encrypted direct connection while this PC is online.
                  Keep Receive enabled to accept transfers.
                </p>
              </div>
            </div>

            <div class="summary-stack">
              <div class="summary-row">
                <span>Secure listener</span>
                <span>{settings?.remoteAccessEnabled ? "Enabled" : "Off"}</span>
              </div>
              <div class="summary-row">
                <span>Remote port</span>
                <span>TCP {settings?.remoteListenPort ?? 50502}</span>
              </div>
              {#if remoteInviteMode === "direct"}
                <div class="summary-row">
                  <span>Router mapping</span>
                  <span>
                    {remoteAccessStatus.state === "mapped"
                      ? "Mapped"
                      : remoteAccessStatus.state === "manual_required"
                        ? "Manual setup"
                        : remoteAccessStatus.state === "listening"
                          ? "Checking"
                          : "Off"}
                  </span>
                </div>
                <div class="summary-row">
                  <span>Auto port mapping</span>
                  <span>{settings?.remoteAutoPortMapping ? "On" : "Off"}</span>
                </div>
              {:else}
                <div class="summary-row">
                  <span>Connection path</span>
                  <span>Private VPN / overlay</span>
                </div>
              {/if}
              {#if remoteInviteMode === "direct" && remoteAccessStatus.publicHost}
                <div class="summary-row">
                  <span>Public endpoint</span>
                  <span>{remoteAccessStatus.publicHost}:{remoteAccessStatus.externalPort}</span>
                </div>
              {/if}
            </div>

            <div class="inline-actions">
              <button class="primary-button" disabled={remoteBusy} on:click={handleRemoteAccessToggle}>
                {settings?.remoteAccessEnabled ? "Disable remote sharing" : "Enable remote sharing"}
              </button>
              {#if settings?.remoteAccessEnabled}
                <button class="soft-button" disabled={remoteBusy} on:click={handleRemoteAutoPortMappingToggle}>
                  {settings.remoteAutoPortMapping ? "Use manual mapping" : "Use auto mapping"}
                </button>
                <button class="soft-button" disabled={remoteBusy} on:click={handleRefreshRemoteMapping}>
                  Check router
                </button>
                {#if canConfigureFirewall}
                  <button class="soft-button" disabled={firewallBusy} on:click={handleEnsureFirewallRules}>
                    Add firewall rule
                  </button>
                {/if}
              {:else}
                <button class="soft-button danger" disabled={remoteBusy} on:click={handleResetRemoteIdentity}>
                  Revoke old invites
                </button>
              {/if}
            </div>

            {#if settings?.remoteAccessEnabled}
              <div class="inline-actions">
                <button
                  class={remoteInviteMode === "direct" ? "primary-button" : "soft-button"}
                  disabled={remoteBusy}
                  on:click={() => selectRemoteInviteMode("direct")}
                >
                  Direct internet
                </button>
                <button
                  class={remoteInviteMode === "overlay" ? "primary-button" : "soft-button"}
                  disabled={remoteBusy}
                  on:click={() => selectRemoteInviteMode("overlay")}
                >
                  VPN / overlay (no forwarding)
                </button>
              </div>

              {#if remoteInviteMode === "overlay"}
                <label class="field-block">
                  <span>PC VPN / overlay address or hostname</span>
                  <input
                    class="text-input"
                    placeholder="100.x.x.x, hostname.tailnet.ts.net, or hostname:port"
                    value={remoteOverlayEndpoint}
                    on:input={(event) => (remoteOverlayEndpoint = event.currentTarget.value)}
                  />
                </label>
                <p class="small-note">
                  <strong>Using Tailscale?</strong> Turn on Tailscale on this PC and the Android phone, and make sure both devices use the same tailnet. Paste this PC’s Tailscale <code>100.x.x.x</code> address or its MagicDNS name here, then create and scan this VPN invite. Keep LanDrop open with Remote sharing and Receive enabled. No router port forwarding is needed.
                </p>
              {:else}
                <label class="field-block">
                  <span>Public address, DNS name, or forwarded port</span>
                  <input
                    class="text-input"
                    placeholder="Detected automatically, or host:port"
                    value={remotePublicHost}
                    on:input={(event) => (remotePublicHost = event.currentTarget.value)}
                  />
                </label>
              {/if}

              <div class="inline-actions">
                <button
                  class="soft-button"
                  disabled={remoteBusy}
                  on:click={handleCreateRemoteInvite}
                >
                  {remoteInviteMode === "overlay" ? "Create VPN invite" : "Create or refresh invite"}
                </button>
              </div>

              {#if remoteInvite}
                <div class="remote-invite-layout">
                  {#if remoteInviteQr}
                    <div class="remote-qr-shell">
                      <img
                        class="remote-qr"
                        src={remoteInviteQr}
                        alt="QR code containing the secure LanDrop remote invitation"
                      />
                      <span>Scan with LanDrop on Android</span>
                    </div>
                  {/if}
                  <div class="remote-invite-copy">
                    <label class="field-block">
                      <span>Remote invite</span>
                      <textarea class="text-input remote-invite" readonly value={remoteInvite}></textarea>
                    </label>
                    <button class="soft-button" on:click={handleCopyRemoteInvite}>
                      Copy invite
                    </button>
                  </div>
                </div>
              {/if}

              <p class="small-note">
                {remoteAccessStatus.message}
                {#if !settings.receiveEnabled}
                  {" "}Receive is paused, so remote senders can connect but transfers will be rejected until you turn Receive back on.
                {/if}
                {#if remoteInviteMode === "overlay"}
                  {" "}Both the PC and phone must be connected to the same Tailscale, Headscale, WireGuard, or other overlay network before scanning this QR code. No router port forwarding is needed.
                {:else if remoteAccessStatus.state !== "mapped"}
                  {" "}A QR invite only works after inbound TCP {settings.remoteListenPort} reaches this PC from the internet. If the home router WAN is a private address (double NAT), forward that port on the outer modem/router too, or use bridge/DMZ. Never forward the LAN port {settings.tcpPort}.
                {/if}
              </p>
            {/if}
          </section>

          <section class="content-card">
            <div class="section-header">
              <div class="section-icon"><Icon name="globe" /></div>
              <div>
                <h2>Remote computer</h2>
                <p>Connect using a remote file sharing invite created on the receiving PC.</p>
              </div>
            </div>

            <label class="field-block">
              <span>Secure invite</span>
              <textarea
                class="text-input remote-invite"
                placeholder="landrop://r/..."
                value={remoteInviteInput}
                on:input={(event) => (remoteInviteInput = event.currentTarget.value)}
              ></textarea>
            </label>

            <div class="inline-actions">
              <button
                class="primary-button"
                disabled={remoteBusy || remoteInviteInput.trim().length === 0}
                on:click={handleAddRemotePeer}
              >
                {remoteBusy ? "Connecting..." : "Connect remote computer"}
              </button>
            </div>
          </section>

          <section class="content-card settings-wide">
            <div class="section-header">
              <div class="section-icon"><Icon name="network" /></div>
              <div>
                <h2>Connection order</h2>
                <p>Drag the handle to choose which adapters LanDrop advertises and tries first.</p>
              </div>
            </div>

            {#if localAdapters.length === 0}
              <div class="empty-state">No network adapters found.</div>
            {:else}
              <div class="adapter-stack">
                {#each localAdapters as adapter, adapterIndex (adapter.id)}
                  <article
                    class:inactive={adapter.status !== "active"}
                    class:dragging={draggedAdapterId === adapter.id}
                    class="adapter-row"
                    on:dragend={() => (draggedAdapterId = null)}
                    on:dragover={handleAdapterDragOver}
                    on:drop|preventDefault={() => handleAdapterDrop(adapter.id)}
                  >
                    <div class="adapter-main">
                      {#if supportsNetworkAdapterOrdering}
                        <div
                          aria-label={`Drag ${adapter.friendlyName}`}
                          class="drag-handle"
                          draggable="true"
                          role="button"
                          tabindex="0"
                          title="Drag to reorder"
                          on:dragstart={(event) => handleAdapterDragStart(event, adapter.id)}
                          on:keydown={(event) => handleAdapterKeyboardMove(event, adapter.id)}
                        >
                          <span></span>
                          <span></span>
                          <span></span>
                        </div>
                      {/if}
                      <div class="adapter-kind-icon" title={formatAdapterKind(adapter.adapterKind)}>
                        <Icon name={adapterIconName(adapter)} size={24} />
                      </div>
                      <div>
                        <strong>{adapter.friendlyName}</strong>
                        <div class="muted-line">
                          {formatAdapterKind(adapter.adapterKind)} | {adapter.ipv4Addresses.join(", ") || "No IPv4"}
                        </div>
                      </div>
                    </div>
                    <div class="adapter-meta">
                      <button class="soft-button compact-button" on:click={() => handleAdapterEnabledToggle(adapter)}>
                        {settings?.disabledAdapterIds.includes(adapter.id) ? "Enable" : "Disable"}
                      </button>
                      <span class={`mini-pill ${adapter.status === "active" ? "accent" : "warning"}`}>
                        {adapterEligibility(adapter)}
                      </span>
                      {#if adapter.isPreferred}
                        <span class="mini-pill accent">Tried first</span>
                      {/if}
                      <span class="muted-line">{formatMbps(adapter.receiveLinkSpeedMbps)}</span>
                      {#if supportsNetworkAdapterOrdering}
                        <span class="mini-pill">#{adapterIndex + 1}</span>
                      {/if}
                    </div>
                  </article>
                {/each}
              </div>
            {/if}
          </section>

          {#if canConfigureFirewall}
            <DirectWifi />
            <section class="content-card settings-wide">
              <div class="section-header">
                <div class="section-icon"><Icon name="network" /></div>
                <div>
                  <h2>Direct cable IP</h2>
                  <p>Use this only for PC-to-PC Ethernet cable. Set one computer as PC 1 and the other as PC 2.</p>
                </div>
              </div>

              <div class="adapter-stack">
                {#each localAdapters.filter(directLanEligible) as adapter (adapter.id)}
                  <article class:inactive={settings?.disabledAdapterIds.includes(adapter.id)} class="adapter-row">
                    <div>
                      <strong>{adapter.friendlyName}</strong>
                      <div class="muted-line">
                        {adapter.ipv4Addresses.join(", ") || "No IPv4"} | {formatMbps(adapter.receiveLinkSpeedMbps)}
                      </div>
                    </div>
                    <div class="adapter-meta">
                      <button
                        class="soft-button compact-button"
                        disabled={directLanCommandsBusy(adapter)}
                        on:click={() => handleConfigureDirectLan(adapter, "pc1")}
                      >
                        PC 1 IP
                      </button>
                      <button
                        class="soft-button compact-button"
                        disabled={directLanCommandsBusy(adapter)}
                        on:click={() => handleConfigureDirectLan(adapter, "pc2")}
                      >
                        PC 2 IP
                      </button>
                      <button
                        class="soft-button compact-button"
                        disabled={directLanCommandsBusy(adapter)}
                        on:click={() => handleRevertDirectLan(adapter)}
                      >
                        Revert IP
                      </button>
                    </div>
                  </article>
                {/each}
              </div>
            </section>
          {/if}

          <section class="content-card settings-wide">
            <div class="section-header">
              <div class="section-icon"><Icon name="settings" /></div>
              <div>
                <h2>Diagnostics</h2>
                <p>Snapshot local state for debugging discovery, firewall, and transfer issues.</p>
              </div>
              <button class="soft-button" disabled={diagnosticsBusy} on:click={handleLoadDiagnostics}>
                {diagnosticsBusy ? "Loading..." : "Load report"}
              </button>
            </div>

            {#if diagnosticsReport}
              <pre class="diagnostics-box">{diagnosticsReport}</pre>
            {:else}
              <div class="empty-state">Load a report when you need to inspect adapters, peers, and recent transfers.</div>
            {/if}
          </section>
        </section>
      </section>
    {/if}
  </main>
</div>

{#if incomingOffer}
  <IncomingTransferDialog
    busy={respondingToIncoming}
    offer={incomingOffer}
    on:accept={handleAcceptIncoming}
    on:reject={handleRejectIncoming}
  />
{/if}
