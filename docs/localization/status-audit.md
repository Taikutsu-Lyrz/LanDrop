# Status and accessibility localization audit

The status audit compares the rendered phrase against the dictionary actually loaded by that platform (`shared` + `android` or `shared` + `desktop`). It inspects Kotlin status producers, Svelte renderers, desktop command results, and persisted transfer messages. `status-missing-keys.json` is the reviewed set of extra source phrases; dynamic Rust fields are expressed using catalog tokens (`${field}`). The source-location inventory used for review is `docs/localization/status-reviewed-locations.json`.

| Finding | Affected surface | Fix |
| --- | --- | --- |
| Missing `Not connected` | Android Settings Wi-Fi Direct shortcut | Add the exact phrase to every locale. |
| Known phrases in the other platform's section | Android `Wi-Fi`, `Choose files`, `Transfer rejected.`; Windows `Pair code required or invalid.`, `Transfer canceled by user.` | Move cross-platform phrases to `shared` or translate in both platform sections. |
| Raw lowercase protocol states | Android manual/selected device connection summaries interpolate `peer.availability`; Windows Direct Wi-Fi lists `connected` as `· connected` | Map protocol values to standalone translated labels at rendering time. Do not change protocol values. |
| A localized phrase is embedded alongside an untranslated one | Android network shortcut translates the transport but inserts `No IPv4 address found` unmodified. The receive-offer size/link and peer IP/link rows also combine translated labels with addresses or counts. | Translate each human-facing component, keeping addresses and metrics intact, then combine. |
| Adjacent opaque dynamic messages | Windows `App.svelte` joins the remote/router status, firewall status, and receive prompt into one `statusLine`. Whole-phrase matching cannot translate each independently. | Keep English status segments or structured message IDs until rendering, translate each separately, and recompute when language changes. |
| Translation bypass in accessibility | Android `WifiDirectQr.kt` assigns a hardcoded QR `contentDescription` despite a catalog entry. Other descriptions and QR prompts use the active dictionary. | Route the QR content description through `localizeAppText` as well. |
| Native surfaces outside the app tree | Tauri picker titles (`commands.rs`) and native notifications (`notifications.rs`) never pass through the desktop DOM observer; OS-originated dialog chrome and errors are controlled by the OS. Android About toast already resolves its message through the active dictionary. | Pass localized app-owned titles at the native boundary; preserve raw OS details or expose them as secondary diagnostics. |
| Incomplete locale not covered by parity check | At audit start `prs-AF` missed 25 shared, 108 Android, and 21 desktop keys from the English catalog, while the four new locales had parity. | Include Dari in parity checks and fill the missing entries; check actual values as well as key presence. |

## Remaining native message handling

The reviewed desktop additions cover router/remote status and setup errors, Windows Wi-Fi hosting errors, saved-path and transfer state messages, missing adapters, and platform command failures. Format captures preserve values such as the peer name, route, port, and underlying OS detail. Generic error details, system dialog chrome, diagnostic report content, and arbitrary names or paths should remain intact. Where a nested detail is another known app-owned message, translate the inner message at the rendering boundary instead of assuming interpolation translates it. No reliable translation can be inferred for arbitrary third-party messages.

## Regression cases

- Open the main Settings page offline and check the Wi-Fi Direct, remote, network and file-stream summaries; repeat while joining/hosting and after connection.
- Switch between Japanese, Spanish, Turkish, Portuguese, Dari and English while the same summary is already mounted. Verify `dir=rtl` only for Dari and that stale English/previous-language values do not persist.
- Check connected-device, receive-offer and adapter rows with a user-entered device name, interface or path. Verify only the app labels change.
- Check Wi-Fi Direct and remote invite QR accessibility descriptions, transfer history statuses and retry/failure messages.
- Validate all locale sections, Android packaged copies and placeholder equivalence. Existing locale parity alone cannot catch source phrases missing from the English catalog.

## Results

All five non-English catalogs now match 1,045 English entries across sections, including the reviewed statuses and folder guidance. Android translates connection reasons inside their parent messages, and Windows keeps remote/router, firewall, and receive messages separate until rendering. Android 11+ explains the folder-picker restriction in the app. An Android 36.1 emulator test verified writing, resuming, and publishing a file in Downloads/LanDrop without a persisted folder grant.
