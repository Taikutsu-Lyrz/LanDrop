<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import QRCode from "qrcode";
  import { refreshDiscovery } from "../api/commands";

  type ConnectedDevice = { name: string; status: string };
  type HostStatus = { state: string; ssid: string | null; password: string | null; addresses: string[]; devices: ConnectedDevice[] };
  let host: HostStatus = { state: "stopped", ssid: null, password: null, addresses: [], devices: [] };
  let busy = false;
  let error = "";
  let qr = "";
  let alive = true;

  $: stateLabel =
    host.state === "started" ? "On"
    : host.state === "starting" ? "Starting…"
    : host.state === "unavailable" ? "Not available"
    : "Off";

  async function update(action: "start" | "stop" | "status") {
    if (busy) return;
    busy = true;
    try {
      const result = await invoke<HostStatus>("direct_wifi", { action });
      if (!alive) return;
      const changed = result.ssid !== host.ssid || result.password !== host.password;
      const linkChanged = result.state !== host.state || result.addresses.join() !== host.addresses.join();
      host = result;
      if (action !== "status") error = "";
      if (changed || !host.ssid) {
        // One QR for everything: the phone camera / system Wi-Fi settings read
        // it natively, and the LanDrop Android app parses the same WIFI:
        // format in its in-app scanner and joins without leaving the app.
        qr = host.ssid && host.password
          ? await QRCode.toDataURL(`WIFI:T:WPA;S:${host.ssid};P:${host.password};;`, { width: 220, margin: 2 })
          : "";
      }
      if (linkChanged) await refreshDiscovery();
    } catch (cause) {
      if (alive) error = String(cause);
    } finally { busy = false; }
  }

  onMount(() => {
    alive = true;
    void update("status");
    const timer = setInterval(() => { void update("status"); }, 3000);
    return () => { alive = false; clearInterval(timer); };
  });
</script>

<section class="content-card settings-wide">
  <h2>Direct Wi-Fi to Android</h2>
  <p class="sub">Phone connects straight to this PC — no router needed.</p>
  <div class="actions">
    <button class="primary-button" disabled={busy || host.state === "started" || host.state === "starting"} on:click={() => update("start")}>Start</button>
    <button class="soft-button" disabled={busy || host.state === "stopped"} on:click={() => update("stop")}>Stop</button>
    <span role="status" class="state-pill" data-state={host.state}>{stateLabel}</span>
  </div>
  <details>
    <summary>Phone cannot see or rejoin this network?</summary>
    <p>Look in the phone's normal Wi-Fi list, or scan the QR in LanDrop. The PC network may not appear in Android's Wi-Fi Direct device list.</p>
    <p>Stop Wi-Fi Direct in LanDrop on the phone, then stop and start it here and scan the new QR. On Android 10-12, also enable Location.</p>
    <p>If only some phones see the network, try Windows Mobile Hotspot set to 2.4 GHz with this direct host stopped. Connect the phone to that hotspot and use LanDrop normally.</p>
  </details>
  {#if error}<p role="alert">{error}</p>{/if}
  {#if host.state === "unavailable"}
    <p>Windows could not start this link. Turn Wi-Fi on, turn Windows Mobile Hotspot off, and retry.</p>
  {/if}
  {#if host.state === "started" && host.ssid && host.password}
    <div class="qr-hero">
      {#if qr}<img src={qr} alt="Scan to join this PC's direct Wi-Fi network" width="220" height="220" />{/if}
      <p class="ssid">{host.ssid}</p>
      <p class="pw">Password: <code>{host.password}</code></p>
      <p class="hint">Scan with LanDrop on Android — or the phone camera. Stay connected if Android says “No internet”.</p>
    </div>
    <div class="connected">
      <h3>{`Connected devices (${host.devices.length})`}</h3>
      {#if host.devices.length === 0}
        <p>No devices connected yet — scan the QR code above with the phone.</p>
      {:else}
        <ul>
          {#each host.devices as device}
            <li><strong>{device.name}</strong> <span role="status">· {device.status}</span></li>
          {/each}
        </ul>
      {/if}
    </div>
    {#if host.addresses.length > 0}
      <p class="addr">PC address: {#each host.addresses as address, i}{i > 0 ? ", " : ""}<code>{address}</code>{/each}</p>
    {/if}
  {/if}
</section>

<style>
  .sub { margin-top: 0; }
  .actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .state-pill {
    font-size: 13px;
    font-weight: 600;
    padding: 4px 12px;
    border-radius: 999px;
    background: var(--surface-2, #2a2a2a);
  }
  .state-pill[data-state="started"] { background: #1d5c2e; color: #fff; }
  .state-pill[data-state="starting"] { background: #7a5b00; color: #fff; }
  .state-pill[data-state="unavailable"] { background: #7a1f1f; color: #fff; }
  .qr-hero {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    margin: 28px auto;
    max-width: 340px;
  }
  .qr-hero img { border-radius: 12px; background: #fff; padding: 8px; }
  .qr-hero .ssid { font-size: 18px; font-weight: 700; margin: 14px 0 4px; overflow-wrap: anywhere; }
  .qr-hero .pw { margin: 0; font-size: 15px; }
  .qr-hero .hint { margin: 12px 0 0; font-size: 13px; opacity: 0.8; }
  .connected { margin-top: 8px; }
  .connected ul { margin: 8px 0 0; padding-left: 20px; }
  .addr { font-size: 13px; opacity: 0.8; margin-top: 12px; }
  code { overflow-wrap: anywhere; }
</style>
