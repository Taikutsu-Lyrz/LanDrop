<script lang="ts">
  export let name:
    | "receive"
    | "send"
    | "settings"
    | "file"
    | "folder"
    | "clear"
    | "refresh"
    | "device"
    | "desktop"
    | "laptop"
    | "phone"
    | "tablet"
    | "shield"
    | "plus"
    | "network"
    | "ethernet"
    | "wifi"
    | "globe"
    | "adapter"
    | "sliders";
  export let size = 22;
</script>

<svg
  aria-hidden="true"
  class="icon"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width="2"
  stroke-linecap="round"
  stroke-linejoin="round"
>
  {#if name === "receive"}
    <path d="M12 3v12" />
    <path d="m7 10 5 5 5-5" />
    <path d="M5 21h14" />
  {:else if name === "send"}
    <path d="M4 12h14" />
    <path d="m13 6 6 6-6 6" />
  {:else if name === "settings"}
    <path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" />
    <path d="M19 15a2 2 0 0 0 .4 2.1l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1A2 2 0 0 0 14.5 19a2 2 0 0 0-1.3 1.9V21a2 2 0 1 1-4 0v-.1A2 2 0 0 0 7.9 19a2 2 0 0 0-2.1.4l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A2 2 0 0 0 3.5 14.5 2 2 0 0 0 1.6 13H1.5a2 2 0 1 1 0-4h.1A2 2 0 0 0 3.5 7.5 2 2 0 0 0 3 5.4l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A2 2 0 0 0 7.9 3a2 2 0 0 0 1.3-1.9V1a2 2 0 1 1 4 0v.1A2 2 0 0 0 14.5 3a2 2 0 0 0 2.1-.4l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1A2 2 0 0 0 19 7.5 2 2 0 0 0 20.9 9h.1a2 2 0 1 1 0 4h-.1A2 2 0 0 0 19 15Z" />
  {:else if name === "file"}
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
    <path d="M14 2v6h6" />
    <path d="M8 13h8" />
    <path d="M8 17h5" />
  {:else if name === "folder"}
    <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
  {:else if name === "clear"}
    <path d="M18 6 6 18" />
    <path d="m6 6 12 12" />
  {:else if name === "refresh"}
    <path d="M21 12a9 9 0 0 1-15.5 6.2" />
    <path d="M3 12A9 9 0 0 1 18.5 5.8" />
    <path d="M3 19v-5h5" />
    <path d="M21 5v5h-5" />
  {:else if name === "device"}
    <rect x="6" y="3" width="12" height="18" rx="2" />
    <path d="M10 18h4" />
  {:else if name === "desktop"}
    <rect x="3" y="4" width="18" height="12" rx="2" />
    <path d="M8 21h8" />
    <path d="M12 16v5" />
  {:else if name === "laptop"}
    <rect x="5" y="5" width="14" height="10" rx="2" />
    <path d="M3 19h18" />
    <path d="M8 15h8" />
  {:else if name === "phone"}
    <rect x="7" y="2" width="10" height="20" rx="2" />
    <path d="M11 18h2" />
  {:else if name === "tablet"}
    <rect x="5" y="2" width="14" height="20" rx="2" />
    <path d="M11 18h2" />
  {:else if name === "shield"}
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
    <path d="m9 12 2 2 4-4" />
  {:else if name === "plus"}
    <path d="M12 5v14" />
    <path d="M5 12h14" />
  {:else if name === "network"}
    <path d="M6 18h12" />
    <path d="M12 6v12" />
    <rect x="4" y="3" width="16" height="6" rx="2" />
    <rect x="3" y="15" width="6" height="6" rx="2" />
    <rect x="15" y="15" width="6" height="6" rx="2" />
  {:else if name === "ethernet"}
    <path d="M5 3h14v8H5z" />
    <path d="M8 11v4" />
    <path d="M12 11v4" />
    <path d="M16 11v4" />
    <path d="M6 15h12v6H6z" />
  {:else if name === "wifi"}
    <path d="M5 12.5a10 10 0 0 1 14 0" />
    <path d="M8 16a6 6 0 0 1 8 0" />
    <path d="M11 19.5a2 2 0 0 1 2 0" />
  {:else if name === "globe"}
    <circle cx="12" cy="12" r="9" />
    <path d="M3 12h18" />
    <path d="M12 3a15 15 0 0 1 0 18" />
    <path d="M12 3a15 15 0 0 0 0 18" />
  {:else if name === "adapter"}
    <rect x="4" y="5" width="16" height="14" rx="3" />
    <path d="M8 9h8" />
    <path d="M8 13h5" />
    <path d="M8 17h2" />
  {:else if name === "sliders"}
    <path d="M4 7h10" />
    <path d="M18 7h2" />
    <path d="M4 17h2" />
    <path d="M10 17h10" />
    <circle cx="16" cy="7" r="2" />
    <circle cx="8" cy="17" r="2" />
  {/if}
</svg>
