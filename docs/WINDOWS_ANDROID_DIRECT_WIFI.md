# Windows to Android direct Wi-Fi

In Windows LanDrop, open **Settings → Direct Wi-Fi to Android → Start direct Wi-Fi**.
Wait for `started`. Join the displayed network using Android's Wi-Fi settings or
scan the Wi-Fi QR code with the phone's Camera. Accept staying connected without
internet. This QR code is a Wi-Fi configuration, not a LanDrop remote invite.

Open LanDrop on Android and select the PC from nearby devices. If discovery
does not find it, enter the PC address shown in the panel using Add device.
The same connection supports sending in either direction. Keep Windows LanDrop
running. Stop disconnects the network and interrupts active transfers on it.
Reconnect Android to its usual Wi-Fi afterward if it does not reconnect itself.

This uses Windows Wi-Fi Direct autonomous group-owner mode with legacy Wi-Fi
clients, so Android can join with its existing Wi-Fi settings. Android's native
phone-to-phone Wi-Fi Direct workflow, normal LAN, and Tailscale remain available.
The transfer protocol is unchanged. SSID and password are temporary and regenerated
when starting a new host session; they are not saved in settings or logs.

Windows chooses the radio channel/band. A direct connection bypasses the router,
but does not guarantee 5 GHz or a particular transfer speed. If hosting is
unavailable, check Wi-Fi is enabled, Windows Mobile Hotspot is disabled, and the
adapter/driver supports Wi-Fi Direct group-owner mode. If connection succeeds but
LanDrop cannot connect, use the existing LanDrop firewall setup in Settings.

Validation: Svelte/TypeScript checks passed; Rust tests cover adapter eligibility
and status/stop behavior. An explicit hardware smoke test started and stopped a
real host on the development laptop. Android joining and end-to-end throughput
have not yet been measured with this feature.

The explicit host smoke test (temporarily starts a real wireless network) is:

```powershell
cargo test --manifest-path src-tauri/Cargo.toml --lib hardware_host_starts_and_stops -- --ignored
```

Reference: [Microsoft desktop Wi-Fi Direct legacy AP sample](https://github.com/microsoft/Windows-classic-samples/tree/main/Samples/WiFiDirectLegacyAP).
