<script lang="ts">
  import { createEventDispatcher } from "svelte";
  import type { IncomingTransferOffer } from "../api/types";
  import { formatBytes } from "../utils/format";

  export let offer: IncomingTransferOffer;
  export let busy = false;

  const dispatch = createEventDispatcher<{ accept: void; reject: void }>();

  $: previewItems = offer.topLevelItems.slice(0, 8);
  $: hiddenItemCount = Math.max(offer.topLevelItems.length - previewItems.length, 0);
</script>

<div class="modal-backdrop">
  <section class="modal-card incoming-offer-card">
    <span class="mini-pill accent">Incoming transfer</span>
    <h3>{offer.senderName}</h3>
    <p>
      Wants to send {offer.fileCount} files
      {#if offer.directoryCount > 0}
        across {offer.directoryCount} folders
      {/if}
      for a total of {formatBytes(offer.totalBytes)}.
    </p>

    <details class="offer-items-menu">
      <summary>
        <span>Selected items</span>
        <span>{offer.topLevelItems.length} item{offer.topLevelItems.length === 1 ? "" : "s"}</span>
      </summary>

      <div class="preview-list offer-items-list">
        {#each previewItems as item}
          <div class="preview-row">
            <span title={item.name}>{item.name}</span>
            <span>
              {item.kind}
              {#if item.size}
                | {formatBytes(item.size)}
              {/if}
            </span>
          </div>
        {/each}

        {#if hiddenItemCount > 0}
          <div class="small-note">+{hiddenItemCount} more item{hiddenItemCount === 1 ? "" : "s"} in this transfer.</div>
        {/if}
      </div>
    </details>

    <div class="inline-actions">
      <button class="primary-button" disabled={busy} on:click={() => dispatch("accept")}>
        {busy ? "Working..." : "Accept"}
      </button>
      <button class="soft-button" disabled={busy} on:click={() => dispatch("reject")}>
        Reject
      </button>
    </div>
  </section>
</div>
