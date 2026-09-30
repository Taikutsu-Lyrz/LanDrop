# LanDrop Real-World Validation Kit

This folder is the handoff package for the two pieces that cannot be honestly completed from a single workstation:

1. Real two-PC end-to-end validation on an actual LAN
2. Long soak tests with huge folders and interruption cases

The application code is in place. This kit makes the field validation repeatable, measurable, and easy for the next operator or agent to continue.

## What this kit includes

- A dataset generator for reproducible smoke, large, and soak-transfer trees
- A manifest exporter to capture the exact file and folder shape of a source or receive tree
- A manifest comparer to prove that a transfer finished without corruption or silent loss
- A structured real-LAN test matrix for direct cable, switch, and router topologies
- A soak and interruption checklist with pass/fail criteria

Scripts live in `tools/`:

- `tools/Generate-LanDropTestDataset.ps1`
- `tools/Export-LanDropManifest.ps1`
- `tools/Compare-LanDropManifest.ps1`

Generated artifacts are intentionally ignored by git:

- `validation/datasets/`
- `validation/manifests/`
- `validation/runs/`

## Test environments

Run the matrix on at least these paths:

1. Direct Ethernet cable between two Windows PCs
2. Two PCs connected through the same unmanaged switch
3. Two PCs connected through the same router LAN
4. Mixed environment where Wi-Fi is also active, to confirm Ethernet preference

Recommended hardware coverage:

- 1 GbE
- 2.5 GbE if available
- 10 GbE if available

Recommended OS coverage:

- Windows 11 to Windows 11
- Windows 10 to Windows 11 if the product needs mixed-version tolerance

## Preflight on both PCs

Before every validation run:

1. Confirm both devices are on the same IPv4 subnet.
2. Confirm LanDrop is allowed through Windows Defender Firewall.
3. Confirm the receive side has a local disk with enough free space for the dataset and temporary `.landrop-part` files.
4. Confirm both apps show the expected device name and TCP port.
5. If pair-code receive protection is enabled, confirm the operator has the current 6-digit code.

Useful Windows commands:

```powershell
ipconfig
Get-NetAdapter | Sort-Object Status, InterfaceDescription
Get-NetIPAddress -AddressFamily IPv4 | Sort-Object InterfaceAlias
```

## Dataset generation

Use the generator to create repeatable datasets on the sender machine.

### Smoke dataset

Fast sanity check for discovery, request approval, folder recreation, and correctness.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Generate-LanDropTestDataset.ps1 `
  -Profile smoke `
  -OutputPath .\validation\datasets\smoke `
  -Force
```

### Large dataset

Good for overnight or daytime throughput and correctness testing without going straight to the heaviest run.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Generate-LanDropTestDataset.ps1 `
  -Profile large `
  -OutputPath .\validation\datasets\large `
  -Scale 0.50 `
  -Force
```

### Soak dataset

For long-run stability and interruption testing. Adjust `-Scale` upward when the lab machine and disk can handle it.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Generate-LanDropTestDataset.ps1 `
  -Profile soak `
  -OutputPath .\validation\datasets\soak `
  -Scale 1.00 `
  -Force
```

## Integrity verification

Export a source manifest before sending:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Export-LanDropManifest.ps1 `
  -RootPath .\validation\datasets\smoke `
  -OutputPath .\validation\manifests\source-smoke.json
```

After the receive completes, export a manifest for the received tree:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Export-LanDropManifest.ps1 `
  -RootPath "D:\LanDrop Receives\Smoke Run" `
  -OutputPath .\validation\manifests\receiver-smoke.json
```

Compare them:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Compare-LanDropManifest.ps1 `
  -ReferenceManifest .\validation\manifests\source-smoke.json `
  -CandidateManifest .\validation\manifests\receiver-smoke.json
```

Pass means:

- No missing files
- No extra files
- Matching relative paths
- Matching sizes
- Matching SHA-256 hashes
- Optional matching timestamps if your run requires timestamp preservation

## Real two-PC validation matrix

### E2E-01 Discovery over router LAN

- Topology: both PCs on the same router
- Dataset: smoke
- Expectation: both devices discover each other within 5 seconds
- Pass: sender can select receiver and open a transfer offer without manual IP entry

### E2E-02 Discovery over direct cable

- Topology: direct cable
- Dataset: smoke
- Expectation: discovery still works if both interfaces self-assign or are manually addressed on the same subnet
- Pass: transfer completes and manifest compare passes

### E2E-03 Manual IP fallback

- Topology: same LAN, but block or ignore discovery for the test
- Dataset: smoke
- Expectation: sender can add receiver by IP and still transfer
- Pass: peer probe succeeds and transfer completes

### E2E-04 Pair-code-protected receive

- Topology: router or switch
- Dataset: smoke
- Expectation: wrong code is rejected, correct code is accepted
- Pass: receiver sees the request only when policy allows it, and correct-code receive completes

### E2E-05 Receive disabled state

- Topology: any
- Dataset: none
- Expectation: sender sees receiver availability state correctly and cannot unintentionally push data
- Pass: UI and behavior both match disabled receive policy

### E2E-06 Large single file

- Topology: preferred Ethernet path
- Dataset: large or soak, with at least one multi-gigabyte file
- Expectation: UI stays responsive, progress updates continue, receive finishes with matching hash
- Pass: no corruption, no stuck temp file, no false success

### E2E-07 Large mixed folder tree

- Topology: preferred Ethernet path
- Dataset: large or soak
- Expectation: nested folders, empty directories, many small files, and large files all reconstruct correctly
- Pass: manifest compare passes with zero path mismatches

## Interruption matrix

Run these on a large or soak dataset, not only on smoke.

### INT-01 Sender cancel

- Start transfer
- Cancel from sender UI after at least one large file has begun streaming
- Pass:
  - sender marks the transfer as canceled
  - receiver does not report success
  - partial `.landrop-part` files are not left behind as completed files

### INT-02 Receiver reject

- Start offer
- Reject on receiver
- Pass:
  - sender surfaces rejection clearly
  - no receive directory is created beyond harmless scaffolding

### INT-03 Receiver app closed mid-transfer

- Start large transfer
- Close receiver app mid-stream
- Pass:
  - sender transitions to failed or canceled instead of hanging forever
  - retry path is available
  - receiver does not leave falsely completed files behind

### INT-04 Sender app closed mid-transfer

- Start large transfer
- Close sender app mid-stream
- Pass:
  - receiver fails cleanly
  - no completed-file rename occurs for incomplete data

### INT-05 Ethernet unplug

- Start large transfer
- Unplug cable or disable the Ethernet adapter while bytes are moving
- Pass:
  - both sides surface failure or cancellation
  - no UI deadlock
  - no success receipt is emitted

### INT-06 Wi-Fi present but Ethernet preferred

- Keep Wi-Fi enabled on both PCs
- Ensure Ethernet is also active
- Pass:
  - discovery prefers Ethernet-ranked peers where possible
  - transfer succeeds on the preferred LAN path

## Soak test schedule

Use at least one of these:

### Soak-A Eight-hour daytime run

- Dataset: large at `-Scale 0.50`
- Pattern: repeat the same folder transfer for 8 hours
- Goal: catch memory growth, handle leaks, stale peer churn, and notification fatigue

### Soak-B Overnight large-folder loop

- Dataset: soak at `-Scale 1.00`
- Pattern: repeated send / accept / verify cycles overnight
- Goal: catch long-tail file system and networking regressions

### Soak-C Interruption loop

- Dataset: large or soak
- Pattern: every third run, inject one interruption from the matrix above
- Goal: confirm the app fails honestly and remains usable after recovery

For every soak run, record:

- Start and end timestamps
- Dataset profile and scale
- Topology
- Sender and receiver PC names
- Link speed from Windows
- Number of completed cycles
- Number of failed cycles
- First failure timestamp
- Manifest compare result for every successful receive

## Pass and fail bar

The MVP should not be called field-validated until:

1. All smoke cases pass on router, switch, and direct-cable topologies.
2. At least one multi-gigabyte transfer passes with matching manifest output.
3. At least one long mixed-folder transfer passes with zero manifest mismatches.
4. Cancel, reject, app-close, and link-loss cases all fail honestly without false success.
5. No receive-side `.landrop-part` files are promoted to final names after interruption.
6. No run leaves the app unable to discover or send again without a full OS reboot.

## Suggested evidence folder layout

Store run artifacts outside git, for example:

```text
validation/
  runs/
    2026-05-14-router-smoke/
      source-manifest.json
      receiver-manifest.json
      compare-summary.txt
      notes.md
    2026-05-14-overnight-soak/
      cycle-log.md
      failure-screenshots/
      manifest-summaries/
```

## Notes for the next agent

- This kit is intentionally operator-driven because a real two-PC LAN cannot be simulated honestly from a single local workspace.
- The scripts here give the next agent a reproducible way to generate data, verify receives, and keep evidence organized.
- If the product later gains structured logging or telemetry export, extend this kit to capture per-transfer latency, throughput, and error codes automatically.
