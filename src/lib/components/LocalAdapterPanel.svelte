<script lang="ts">
  import type { LocalAdapter } from "../api/types";
  import {
    formatAdapterKind,
    formatMbps,
    formatList
  } from "../utils/format";

  export let adapters: LocalAdapter[] = [];
</script>

<section class="panel">
  <div class="panel-body">
    <div class="panel-header">
      <div>
        <h2 class="panel-title">Local network interfaces</h2>
        <p class="panel-copy">
          These rows come from the Rust backend on Windows. Preferred Ethernet candidates float to the top.
        </p>
      </div>
    </div>

    {#if adapters.length === 0}
      <div class="empty-state">
        No adapter diagnostics are available yet.
      </div>
    {:else}
      <div class="transfer-list">
        {#each adapters as adapter (adapter.id)}
          <article class="transfer-row">
            <div class="transfer-topline">
              <div>
                <h3 class="transfer-title">{adapter.friendlyName}</h3>
                <p class="transfer-subtitle">
                  {formatAdapterKind(adapter.adapterKind)} | {adapter.ifType} | metric {adapter.ipv4Metric}
                </p>
              </div>

              <div class="button-row">
                {#if adapter.isPreferred}
                  <span class="pill">Preferred</span>
                {/if}
                <span class:warning={adapter.status === "ignored"} class="pill">
                  {adapter.status}
                </span>
              </div>
            </div>

            <div class="adapter-grid">
              <div class="descriptor">
                <strong>{formatList(adapter.ipv4Addresses)}</strong>
                <span>IPv4 addresses</span>
              </div>
              <div class="descriptor">
                <strong>{formatList(adapter.gatewayAddresses)}</strong>
                <span>Gateways</span>
              </div>
              <div class="descriptor">
                <strong>{formatMbps(adapter.receiveLinkSpeedMbps)}</strong>
                <span>Receive link</span>
              </div>
              <div class="descriptor">
                <strong>{formatMbps(adapter.transmitLinkSpeedMbps)}</strong>
                <span>Transmit link</span>
              </div>
            </div>

            <div class="transfer-meta" style="margin-top: 14px;">
              <span>{adapter.description || "No adapter description provided."}</span>
              <span>{adapter.statusReason ?? "Eligible for LAN transfer."}</span>
            </div>
          </article>
        {/each}
      </div>
    {/if}
  </div>
</section>
