# Wi-Fi Direct compatibility and reconnects

Confirmed code defects fixed: Android disposal left the Windows network request and process route alive; late connection callbacks and TCP probes could restore stale UI; global Wi-Fi IP lookup could use another network. Stop now releases requests/routing, cancels discovery/invitations, requests group removal and invalidates callbacks/probes. QR connection waits for old group removal, with an eight-second reset timeout. Android QR hosting uses 2.4 GHz. Phone search and peer invitations are removed; QR hosting/joining remain.

## Missing PC network

The PC enables Windows Wi-Fi Direct legacy AP mode. Join through normal Wi-Fi or QR; appearance in the phone's P2P list is not required. A Wi-Fi 6 label does not establish why another phone cannot see it. A channel/driver compatibility issue is plausible, but requires the affected phones and radio diagnostics to confirm. Windows chooses the channel; this API has no public band/channel selector.

- Microsoft: https://learn.microsoft.com/en-ie/answers/questions/2131866/how-does-wifidirectadvertisementpublisher-decide-w
- Android constraints: https://developer.android.com/about/versions/10/privacy/changes

For comparison, stop the PC direct host, enable Windows Mobile Hotspot at 2.4 GHz, connect the phone and use LanDrop over that network. Compare Xiaomi, S10 and S25 with the same setup. On Android 10-12, enable Location as well as granting permission.

## Hardware validation still required

1. Join the PC QR, transfer both directions, stop on Android, and repeat five times per phone.
2. Stop during the join dialog, host creation, and TCP probing; ensure no late callback restores the stopped link.
3. Host a phone QR, stop before a client joins, then host again.
4. Switch between Android-hosted groups and PC QR joins; verify the old group disappears.
5. Exercise permission denial, Location disabled, timeout, and app close/reopen.
6. Compare normal Wi-Fi visibility to P2P-list visibility; record phone model/OS, PC adapter/driver and channel. Exclude passwords from shared logs.

Stop releases LanDrop connections. Android does not let this app force-toggle system Wi-Fi; if the vendor stack refuses group removal, the UI advises toggling Wi-Fi manually.
