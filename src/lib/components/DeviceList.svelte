<script lang="ts">
  import { createEventDispatcher } from "svelte";
  import Icon from "./Icon.svelte";
  import type { PeerDevice } from "../api/types";
  import { formatAdapterKind, formatRelativeTime } from "../utils/format";

  export let devices: PeerDevice[] = [];
  export let activeDeviceId: string | null = null;

  const dispatch = createEventDispatcher<{ select: string }>();

  type DeviceIconName = "desktop" | "laptop" | "phone" | "tablet";

  function availabilityLabel(device: PeerDevice) {
    switch (device.availability) {
      case "busy":
        return "Busy";
      case "receive_disabled":
        return "Paused";
      case "transferring":
        return "Transferring";
      default:
        return "Ready";
    }
  }

  function availabilityTone(device: PeerDevice) {
    switch (device.availability) {
      case "busy":
        return "warning";
      case "receive_disabled":
        return "warning";
      case "transferring":
        return "accent";
      default:
        return "accent";
    }
  }

  function deviceIconName(device: PeerDevice): DeviceIconName {
    const kind = (device.deviceKind || "").toLowerCase();
    if (kind === "laptop") {
      return "laptop";
    }
    if (kind === "tablet") {
      return "tablet";
    }
    if (kind === "phone" || kind === "android" || device.deviceId.startsWith("android-")) {
      return "phone";
    }
    return "desktop";
  }

  function deviceTypeLabel(device: PeerDevice) {
    const kind = (device.deviceKind || "").toLowerCase();
    if (kind === "android") {
      return "Android";
    }
    if (kind === "laptop") {
      return "Laptop";
    }
    if (kind === "tablet") {
      return "Tablet";
    }
    if (kind === "phone" || device.deviceId.startsWith("android-")) {
      return "Phone";
    }
    return "Windows PC";
  }
</script>

{#if devices.length === 0}
  <div class="empty-state">
    No devices found yet. Make sure the other PC is open on the same local network, or add it manually in Settings.
  </div>
{:else}
  <div class="device-list">
    {#each devices as device (device.deviceId)}
      <button
        class:active={device.deviceId === activeDeviceId}
        class="device-card"
        on:click={() => dispatch("select", device.deviceId)}
      >
        <div class="device-icon"><Icon name={deviceIconName(device)} size={30} /></div>
        <div class="device-body">
          <div class="device-top">
            <strong>{device.name}</strong>
            <span class={`mini-pill ${availabilityTone(device)}`}>{availabilityLabel(device)}</span>
          </div>
          <div class="device-subtitle">{device.ip}:{device.port}</div>
          <div class="device-tags">
            <span class="mini-pill">{deviceTypeLabel(device)}</span>
            <span class="mini-pill">{formatAdapterKind(device.adapterKind)}</span>
            {#if device.requiresPairCode}
              <span class="mini-pill accent">Pair code</span>
            {/if}
            <span class="mini-pill">Seen {formatRelativeTime(device.lastSeenMs)}</span>
          </div>
        </div>
      </button>
    {/each}
  </div>
{/if}
