# LanDrop releases

This document explains how to cut a LanDrop release with the
[`release.yml`](.github/workflows/release.yml) GitHub Actions workflow,
which builds the **Windows Tauri 2 NSIS + MSI installers and a portable zip**
plus the **Android APK/AAB**, and attaches them to a draft GitHub Release.

## TL;DR

| Path | Command |
|---|---|
| Normal release (recommended) | Actions → **Release** → **Run workflow** → set `version` to e.g. `0.2.0` → **Run workflow** |
| Re-release current tree as-is | Actions → **Release** → **Run workflow** → leave `version` empty → **Run workflow** |
| Tag-driven release | `git tag v0.2.0 && git push origin v0.2.0` |
| Publish | Open the draft release on GitHub, check the assets, press **Publish** |

All releases stay **draft** until a human publishes them.

## How to cut a release

### Option A — workflow dispatch with a version (recommended)

1. Go to the repo on GitHub → **Actions** → **Release** → **Run workflow**.
2. Select the branch (default: `master`).
3. Enter `version`, e.g. `0.2.0` (a leading `v` is also accepted).
4. Press **Run workflow**.

The `prepare` job:

- resolves `0.2.0`,
- runs `tools/bump-version.sh 0.2.0`, which sets the version in
  `package.json`, `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml`,
  `android/rust/landrop_mobile_bridge/Cargo.toml`, and
  `android/app/build.gradle.kts` (`versionName` + computed `versionCode`),
- commits the bump as `chore(release): bump version to 0.2.0` and pushes it to
  the dispatch branch (plain push, never force-push),
- creates (or reuses) a **draft** GitHub Release for tag `v0.2.0`.

Then `build-windows` and `build-android` build that exact commit and attach
their outputs to the draft release.

### Option B — workflow dispatch without a version

Leave `version` empty. The workflow releases the **current** `package.json`
version without rewriting any files. Useful for rebuilding/re-attaching
assets for the version already in the tree.

### Option C — push a tag

```bash
git tag v0.2.0
git push origin v0.2.0
```

Tag-triggered runs **never rewrite version files** — they build and release
the tagged tree as-is. Make sure the version files already say `0.2.0`
(e.g. via a prior Option A run, or `tools/bump-version.sh 0.2.0` committed
locally) before tagging.

## Version scheme

- Versions are strict semver `MAJOR.MINOR.PATCH` (e.g. `0.2.0`).
- Tags are `v` + version (`v0.2.0`); the leading `v` is stripped for file contents.
- Android `versionCode` is derived: `MAJOR * 10000 + MINOR * 100 + PATCH`
  (e.g. `0.1.0` → `100`, `0.2.0` → `200`, `1.3.7` → `10307`).

To bump locally (same script CI uses):

```bash
tools/bump-version.sh 0.2.0
git diff   # review, then commit
```

## Artifacts

| Job | Runner | Output |
|---|---|---|
| `build-windows` | `windows-latest` | Three Windows artifacts (see below), each uploaded separately and attached to the draft release |
| `build-android` | `ubuntu-latest` | Verified files staged under `android/release-out/`: a signed release APK/AAB when signing secrets exist, otherwise one clearly labeled debug-signed APK |

### Windows artifacts

Each draft release gets all three:

| Artifact | Path / name | Notes |
|---|---|---|
| **NSIS setup** | `src-tauri/target/release/bundle/nsis/*.exe` → workflow artifact `landrop-windows-nsis-vX.Y.Z` | Traditional guided installer (`currentUser` install mode). |
| **MSI installer** | `src-tauri/target/release/bundle/msi/*.msi` → workflow artifact `landrop-windows-msi-vX.Y.Z` | MSI for enterprise / `msiexec` installs. Built with **WiX**, which is available on `windows-latest` runners (no extra setup step). |
| **Portable zip** | `src-tauri/target/release/landrop-windows-portable-vX.Y.Z.zip` → workflow artifact `landrop-windows-portable-vX.Y.Z` | Standalone app, no installer. Contains `LanDrop.exe` plus a `PORTABLE.txt`. **Requires the system Microsoft Edge WebView2 Runtime** (preinstalled on most Windows 10/11 machines; install from [Microsoft](https://developer.microsoft.com/microsoft-edge/webview2/) if missing). Tauri 2 has no built-in `portable` target — CI zips the release binary after `tauri build`. |

The Windows build runs `npm ci` then `npm run tauri build`
(`tauri.conf.json` targets `["nsis", "msi"]`; WebView2 and WiX are available
on `windows-latest` runners). After the Tauri build, a PowerShell step creates
the portable zip from `LanDrop.exe` (falling back to `landrop-desktop.exe` if
needed) and fails clearly if neither binary is found.

The Android build uses the **committed prebuilt JNI libs** at
`android/app/src/main/jniLibs/{arm64-v8a,x86_64}/liblandrop_mobile_bridge.so`
— CI does not rebuild Rust for Android. JDK 17 with compileSdk 36 /
targetSdk 36 (per `android/app/build.gradle.kts`) is set up automatically.

## Secrets

No secrets are required for a testable release: without signing secrets CI
produces the NSIS + MSI installers and portable zip plus a **debug-signed**
Android APK named `LanDrop-vX.Y.Z-android-debug.apk`. The workflow never
publishes `app-release-unsigned.apk`, because an unsigned APK is not
installable by Android.

A debug-signed APK is suitable for testing, but CI debug signing keys are not
a stable production identity. Android may require uninstalling an older debug
build before installing a debug APK produced by another runner. Configure the
four Android signing secrets below for stable, upgradeable release APKs.

For **signed** Android release artifacts (recommended for distribution),
add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Your release keystore (`.jks`/`.keystore`) encoded with `base64 -w0 keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Generate them e.g.:

```bash
keytool -genkeypair -v -keystore landrop-release.jks -alias landrop \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 landrop-release.jks   # paste output into ANDROID_KEYSTORE_BASE64
```

When all four secrets exist, CI signs via AGP's injected-signing properties
(no repo `signingConfig` changes needed) and produces a signed release APK
**plus** a signed release AAB (`bundleRelease`). If `ANDROID_KEYSTORE_BASE64`
is set but the other three are incomplete, the Android job fails fast with a
clear error rather than shipping half-signed artifacts.

Also required is the default `GITHUB_TOKEN` with `contents: write`
(already configured at the top of `release.yml`) for tags, draft releases,
version-bump commits, and asset uploads.

## Publishing checklist

1. Wait for `prepare`, `build-windows`, `build-android` to all go green.
2. Open the draft release (`vX.Y.Z`) on GitHub.
3. Verify assets: Windows NSIS `.exe`, MSI `.msi`, portable `.zip` + Android
   `.apk` (and `.aab` if signed).
4. Smoke-test: install the NSIS and/or MSI build on a Windows machine; run
   the portable zip (confirm WebView2 is present); install the APK on a
   device (`adb install app-*.apk`).
5. Edit release notes if needed, then **Publish release**.

## Troubleshooting

- `Resolved version ... is not valid semver` — the dispatch input or tag must
  look like `0.2.0` (or `v0.2.0` for tags).
- Empty `src-tauri/target/release/bundle/nsis/` or `bundle/msi/` — the Tauri
  build failed; check the `Build Tauri NSIS + MSI bundles` log (usually a
  frontend `vite build` error, a Rust compile error, or missing WiX for MSI).
- `Portable zip failed: neither LanDrop.exe nor landrop-desktop.exe found` —
  the Rust binary did not land in `src-tauri/target/release/`; inspect the
  Tauri build log for link/compile failures before the portable step.
- Android says the package is invalid / cannot be installed — do not install
  old `app-release-unsigned.apk` assets; unsigned APKs are not installable.
  New workflows never publish them. If a new debug APK conflicts with an older
  debug install, uninstall the old debug build first, or configure the four
  `ANDROID_*` signing secrets for stable upgrades.
- Only a debug APK, no release APK — expected when no signing secrets are
  configured; add the four `ANDROID_*` secrets for signed production output.
- Version-bump commit missing — only dispatches **with** a version push a
  commit; tag pushes and empty-input dispatches intentionally never rewrite files.
