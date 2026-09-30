#!/usr/bin/env bash
#
# Bump the LanDrop release version across every version source.
#
# Usage:
#   tools/bump-version.sh 0.2.0
#
# What it updates:
#   - package.json                                    -> "version"
#   - src-tauri/tauri.conf.json                       -> "version"
#   - src-tauri/Cargo.toml                            -> [package].version
#   - android/rust/landrop_mobile_bridge/Cargo.toml   -> [package].version
#   - android/app/build.gradle.kts                    -> versionName + versionCode
#
# versionCode scheme: MAJOR * 10000 + MINOR * 100 + PATCH
#   e.g. 0.1.0 -> 100, 0.2.0 -> 200, 1.3.7 -> 10307
#
# The script is idempotent for a given version and touches nothing else.
# It is called by .github/workflows/release.yml; it can also be run locally.

set -euo pipefail

VERSION="${1:-}"

# Strip a leading 'v' if the caller passed a tag name (v0.2.0 -> 0.2.0).
VERSION="${VERSION#v}"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: expected a semver argument like 0.2.0 (got: '$VERSION')" >&2
  echo "usage: $0 <major.minor.patch>" >&2
  exit 1
fi

MAJOR="${VERSION%%.*}"
REST="${VERSION#*.}"
MINOR="${REST%%.*}"
PATCH="${REST##*.}"
VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "Bumping LanDrop to $VERSION (versionCode $VERSION_CODE)"

# --- JSON files (package.json, tauri.conf.json) via node for safe parsing ---
node -e '
const fs = require("fs");
const [, file, version] = process.argv;
const raw = fs.readFileSync(file, "utf8");
const data = JSON.parse(raw);
data.version = version;
fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n");
' package.json "$VERSION"

node -e '
const fs = require("fs");
const [, file, version] = process.argv;
const raw = fs.readFileSync(file, "utf8");
const data = JSON.parse(raw);
data.version = version;
fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n");
' src-tauri/tauri.conf.json "$VERSION"

# --- Cargo.toml files: only the [package] version (first lines of the file).
# The "1,6" range keeps dependency `version = "..."` requirements untouched.
sed -i "1,6s/^version = \".*\"/version = \"$VERSION\"/" src-tauri/Cargo.toml
sed -i "1,6s/^version = \".*\"/version = \"$VERSION\"/" android/rust/landrop_mobile_bridge/Cargo.toml

# --- Android Gradle versionName + versionCode ---
sed -i "s/^\( *\)versionCode = .*/\1versionCode = $VERSION_CODE/" android/app/build.gradle.kts
sed -i "s/^\( *\)versionName = \".*\"/\1versionName = \"$VERSION\"/" android/app/build.gradle.kts

echo "--- verification ---"
grep '"version"' package.json | head -1
grep '"version"' src-tauri/tauri.conf.json | head -1
grep -n '^version' src-tauri/Cargo.toml | head -1
grep -n '^version' android/rust/landrop_mobile_bridge/Cargo.toml | head -1
grep -n -E 'version(Code|Name)' android/app/build.gradle.kts
echo "Done: $VERSION (versionCode $VERSION_CODE)"
