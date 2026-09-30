#!/usr/bin/env bash
# Real two-AVD remote E2E: runs emulators one at a time (software accel cannot
# sustain two concurrent headless AVDs) and probes via RemoteE2EDebugActivity.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/.android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

BIN="$ROOT/tools/remote-e2e/target/debug/landrop-remote-e2e"
RUN_DIR="$ROOT/validation/runs/remote-emulator-e2e"
INVITE="$RUN_DIR/invite.txt"
READY="$RUN_DIR/ready.txt"
HOST_LOG="$RUN_DIR/host.log"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
PORT=50502

AVDS=(landrop_host_emu landrop_sender_emu)
SERIALS=(emulator-5554 emulator-5556)
PORTS=(5554 5556)

mkdir -p "$RUN_DIR"
chmod 777 "$RUN_DIR" || true

kill_emulators() {
  adb devices 2>/dev/null | awk 'NR>1 && $1 ~ /^emulator-/{print $1}' | while read -r serial; do
    adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
  sleep 2
}

kill_host() {
  if [[ -n "${HOST_PID:-}" ]] && kill -0 "$HOST_PID" 2>/dev/null; then
    kill "$HOST_PID" 2>/dev/null || true
    wait "$HOST_PID" 2>/dev/null || true
  fi
  HOST_PID=""
}

cleanup() {
  kill_host
  kill_emulators
}
trap cleanup EXIT

echo "==> Building remote host harness"
cargo build --manifest-path "$ROOT/tools/remote-e2e/Cargo.toml"

echo "==> Building Android debug APK"
cd "$ROOT/android"
chmod +x ./gradlew
./gradlew :app:assembleDebug --quiet
test -f "$APK"

wait_for_boot() {
  local serial="$1"
  local tries=120
  for _ in $(seq 1 "$tries"); do
    if adb -s "$serial" get-state 2>/dev/null | grep -q device; then
      local boot
      boot="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
      if [[ "$boot" == "1" ]]; then
        return 0
      fi
    fi
    sleep 5
  done
  return 1
}

start_host() {
  kill_host
  rm -f "$INVITE" "$READY"
  : >"$HOST_LOG"
  "$BIN" host \
    --bind 0.0.0.0 \
    --port "$PORT" \
    --public-host 10.0.2.2 \
    --invite "$INVITE" \
    --ready "$READY" \
    --name "Host PC" \
    --emulators 1 >"$HOST_LOG" 2>&1 &
  HOST_PID=$!
  echo "$HOST_PID" >"$RUN_DIR/host.pid"

  for _ in $(seq 1 100); do
    if [[ -s "$INVITE" && -s "$READY" ]]; then
      return 0
    fi
    if ! kill -0 "$HOST_PID" 2>/dev/null; then
      echo "Host exited early"
      cat "$HOST_LOG"
      return 1
    fi
    sleep 0.1
  done
  echo "Timed out waiting for invite"
  cat "$HOST_LOG"
  return 1
}

read_result() {
  local serial="$1"
  local out=""
  out="$(adb -s "$serial" shell "run-as dev.landrop.mobile cat cache/landrop-remote-e2e-result.txt 2>/dev/null" | tr -d '\r')"
  if [[ -n "$out" ]]; then
    printf '%s' "$out"
    return 0
  fi
  out="$(adb -s "$serial" shell "cat /sdcard/Download/landrop-remote-e2e-result.txt 2>/dev/null" | tr -d '\r')"
  if [[ -n "$out" ]]; then
    printf '%s' "$out"
    return 0
  fi
  out="$(adb -s "$serial" shell "cat /sdcard/Android/data/dev.landrop.mobile/files/landrop-remote-e2e-result.txt 2>/dev/null" | tr -d '\r')"
  printf '%s' "$out"
}

run_on_avd() {
  local avd="$1"
  local serial="$2"
  local emu_port="$3"
  local idx="$4"

  echo "==> Emulator $idx/2: starting $avd on port $emu_port"
  kill_emulators
  emulator -avd "$avd" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -accel off -port "$emu_port" \
    >"$RUN_DIR/${serial}.emu.log" 2>&1 &

  wait_for_boot "$serial"
  adb -s "$serial" wait-for-device

  # Soft emulators often boot without a usable default route until Wi-Fi is enabled.
  adb -s "$serial" shell svc wifi enable >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    if adb -s "$serial" shell "ping -c1 -W2 10.0.2.2" >/dev/null 2>&1; then
      break
    fi
    adb -s "$serial" shell svc wifi enable >/dev/null 2>&1 || true
    sleep 2
  done
  if ! adb -s "$serial" shell "ping -c1 -W2 10.0.2.2" >/dev/null 2>&1; then
    echo "Emulator $serial cannot reach 10.0.2.2"
    return 1
  fi

  start_host

  adb -s "$serial" reverse --remove-all >/dev/null 2>&1 || true
  adb -s "$serial" reverse tcp:"$PORT" tcp:"$PORT"
  adb -s "$serial" push "$INVITE" /data/local/tmp/landrop-remote-invite.txt >/dev/null
  adb -s "$serial" install -r -t "$APK" >/dev/null

  adb -s "$serial" shell "rm -f /sdcard/Download/landrop-remote-e2e-result.txt /sdcard/Android/data/dev.landrop.mobile/files/landrop-remote-e2e-result.txt" >/dev/null 2>&1 || true
  adb -s "$serial" shell "run-as dev.landrop.mobile rm -f cache/landrop-remote-e2e-result.txt files/landrop-remote-e2e-result.txt" >/dev/null 2>&1 || true

  adb -s "$serial" shell am force-stop dev.landrop.mobile >/dev/null 2>&1 || true
  adb -s "$serial" shell am start -n dev.landrop.mobile/.RemoteE2EDebugActivity >/dev/null

  local result=""
  for _ in $(seq 1 60); do
    result="$(read_result "$serial")"
    if [[ "$result" == PASS* ]]; then
      break
    fi
    if [[ "$result" == FAIL* ]]; then
      break
    fi
    if ! kill -0 "$HOST_PID" 2>/dev/null; then
      break
    fi
    sleep 5
  done

  echo "App result ($serial): ${result:-<none>}"
  echo "Host log ($serial):"
  cat "$HOST_LOG"
  cp "$HOST_LOG" "$RUN_DIR/${serial}.host.log"

  if [[ "$result" != PASS* ]]; then
    echo "Emulator probe failed on $serial"
    return 1
  fi
  if ! grep -q "HOST PASS" "$HOST_LOG"; then
    echo "Host did not report PASS for $serial"
    return 1
  fi

  kill_host
  adb -s "$serial" emu kill >/dev/null 2>&1 || true
  sleep 2
  echo "PASS $serial ($avd)"
}

kill_emulators

for i in 0 1; do
  run_on_avd "${AVDS[$i]}" "${SERIALS[$i]}" "${PORTS[$i]}" "$((i + 1))"
done

echo "EMULATOR E2E PASS: two Android AVDs probed Host PC over remote TLS invite via 10.0.2.2:$PORT"
