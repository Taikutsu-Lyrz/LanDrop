# LanDrop Transfer Protocol v2

This document defines the next wire protocol for production-grade transfers. The current v1 protocol sends a JSON `file_start` frame and then raw file bytes until the file is complete. That is fast and simple, but it prevents reliable mid-file peer messages because the receiver cannot distinguish file bytes from control messages until the announced byte count has been consumed.

Protocol v2 keeps the same offer / accept / manifest flow, then changes the payload phase into bounded data frames. This enables peer-notified pause/resume, cleaner cancellation, heartbeat/timeout handling, and stronger crash recovery.

## Goals

- Preserve high throughput on Ethernet.
- Avoid loading whole files into memory.
- Allow either side to pause and resume while the other side is explicitly notified.
- Allow transfer state to be persisted after each chunk boundary.
- Keep Android and Windows interoperable through negotiated protocol versions.
- Keep the format simple enough to debug with logs.

## Version Negotiation

The sender starts with `transfer_offer.protocolVersion = 2` and includes:

```json
{
  "type": "transfer_offer",
  "protocolVersion": 2,
  "supportedTransferEncodings": ["framed_v2", "raw_v1"]
}
```

The receiver replies with:

```json
{
  "type": "transfer_response",
  "protocolVersion": 2,
  "accepted": true,
  "selectedTransferEncoding": "framed_v2"
}
```

Fallback rules:

- If the peer does not include `selectedTransferEncoding`, use `raw_v1`.
- If either side only supports protocol v1, use `raw_v1`.
- Only use `framed_v2` when both sides explicitly support it.

## Frame Model

All non-data messages remain length-prefixed JSON control frames:

```text
u32_be json_length
json_payload
```

File bytes are sent as a `file_chunk` control frame followed by exactly `length` raw bytes:

```json
{
  "type": "file_chunk",
  "protocolVersion": 2,
  "transferId": "...",
  "relativePath": "folder/file.bin",
  "offset": 1048576,
  "length": 1048576,
  "sequence": 42
}
```

Then:

```text
<length raw bytes>
```

Default chunk size: 1 MiB.

Recommended max chunk size: 8 MiB.

Small files may use a smaller final chunk. Empty files are represented by `file_start` followed by `file_end` with no `file_chunk`.

## Payload Phase

The v2 send sequence for each file is:

1. `file_start`
2. zero or more `file_chunk` + raw bytes
3. `file_chunk_ack` after each chunk
4. `file_end`

The full transfer ends with:

1. `transfer_complete`
2. `transfer_receipt`

## Flow Control

Either side may send `transfer_flow_control` between chunk boundaries:

```json
{
  "type": "transfer_flow_control",
  "protocolVersion": 2,
  "transferId": "...",
  "state": "paused",
  "reason": "user",
  "sentAtMs": 1760000000000
}
```

States:

- `paused`: peer should stop sending new chunks.
- `resumed`: peer may continue.
- `cancel_requested`: peer should stop and fail honestly.

Rules:

- A sender must wait for either `file_chunk_ack` or `transfer_flow_control` after each chunk.
- A receiver that pauses must send `paused` before it stops reading more chunk frames.
- A receiver that resumes must send `resumed` before it waits for the next chunk.
- A sender that pauses itself must send `paused` before sleeping.
- UI status should reflect both local and peer pause states.
- If both sides pause, resume requires both sides to reach `resumed`.

## Chunk Acknowledgement

`file_chunk_ack` is the safe boundary where v2 peer control is exchanged:

```json
{
  "type": "file_chunk_ack",
  "protocolVersion": 2,
  "transferId": "...",
  "relativePath": "folder/file.bin",
  "nextOffset": 2097152,
  "sequence": 42,
  "acknowledgedAtMs": 1760000000000
}
```

The sender must not send the next chunk for that file until the current chunk is acknowledged or the peer sends flow control. This adds round-trip overhead, but keeps the first v2 implementation simple and correct. A later `windowSize` negotiation can allow several chunks in flight once the protocol has soaked.

## Crash Resume

The receiver already stores partial files with `.landrop-part`. v2 extends this with a session file:

```json
{
  "transferId": "...",
  "peerDeviceId": "...",
  "manifestHash": "...",
  "destinationRoot": "...",
  "files": [
    {
      "relativePath": "folder/file.bin",
      "expectedSize": 123456,
      "completedBytes": 1048576,
      "complete": false
    }
  ]
}
```

The receiver updates this session after each chunk flush. The sender persists its draft manifest and source paths for outgoing retry. On reconnect, the receiver returns a `transfer_resume_plan` based on the session file and actual `.landrop-part` sizes.

## Integrity

MVP v2 integrity:

- Check chunk `offset` against local completed bytes.
- Reject out-of-order chunks unless future parallel mode is negotiated.
- Reject chunks that exceed file size.
- Promote `.landrop-part` to final filename only after file size matches expected size.

Future integrity:

- Optional per-chunk BLAKE3 or SHA-256.
- Optional manifest hash in offer/response.
- Optional whole-file hash verification before final rename.

## TLS Integration

TLS wraps the TCP stream below this protocol. The frame format does not change. Pairing/trust should pin a peer certificate or public key so LAN MITM attacks are not silently accepted.

Internet-facing transfers additionally follow [REMOTE_TRANSFER_V2.md](REMOTE_TRANSFER_V2.md). The existing plaintext LAN listener must never be port-forwarded by LanDrop.

Recommended sequence:

1. Add protocol v2 framed payload.
2. Add persistent peer identity keys.
3. Add TLS with TOFU or pairing-code-bound certificate verification.
4. Add trusted-device certificate management UI.

## Implementation Slices

1. Add v2 message types without changing runtime behavior.
2. Add desktop-to-desktop framed sender and receiver behind negotiation.
3. Add Android framed receiver behind negotiation.
4. Add Android framed sender behind negotiation.
5. Persist receiver session state after each chunk.
6. Persist sender outgoing session state.
7. Convert pause/resume UI to send `transfer_flow_control` over active sessions.
8. Add two-device soak tests for pause/resume, crash/restart, and bad-network recovery.
