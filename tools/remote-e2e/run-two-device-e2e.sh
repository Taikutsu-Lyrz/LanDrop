#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BIN="$ROOT/tools/remote-e2e/target/debug/landrop-remote-e2e"
RUN_DIR="$ROOT/validation/runs/remote-e2e"
INVITE="$RUN_DIR/invite.txt"
PAYLOAD="$RUN_DIR/payload.bin"
RECEIVED="$RUN_DIR/received.bin"
HOST_LOG="$RUN_DIR/host.log"
SENDER_LOG="$RUN_DIR/sender.log"
HOST_NS="landrop-host"
SENDER_NS="landrop-sender"
HOST_IP="10.233.10.1"
SENDER_IP="10.233.10.2"
PORT="50502"

cleanup() {
  if [[ -n "${HOST_PID:-}" ]]; then
    sudo kill "$HOST_PID" 2>/dev/null || true
    wait "$HOST_PID" 2>/dev/null || true
  fi
  sudo ip netns del "$HOST_NS" 2>/dev/null || true
  sudo ip netns del "$SENDER_NS" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$RUN_DIR"
chmod 777 "$RUN_DIR"
rm -f "$INVITE" "$PAYLOAD" "$RECEIVED" "$HOST_LOG" "$SENDER_LOG"

echo "==> Building remote E2E harness"
cargo +nightly build --manifest-path "$ROOT/tools/remote-e2e/Cargo.toml"
test -x "$BIN"

echo "==> Creating two virtual network devices (namespaces)"
sudo ip netns del "$HOST_NS" 2>/dev/null || true
sudo ip netns del "$SENDER_NS" 2>/dev/null || true
sudo ip netns add "$HOST_NS"
sudo ip netns add "$SENDER_NS"
sudo ip link add v-landrop-h type veth peer name v-landrop-s
sudo ip link set v-landrop-h netns "$HOST_NS"
sudo ip link set v-landrop-s netns "$SENDER_NS"
sudo ip netns exec "$HOST_NS" ip addr add "${HOST_IP}/24" dev v-landrop-h
sudo ip netns exec "$SENDER_NS" ip addr add "${SENDER_IP}/24" dev v-landrop-s
sudo ip netns exec "$HOST_NS" ip link set lo up
sudo ip netns exec "$SENDER_NS" ip link set lo up
sudo ip netns exec "$HOST_NS" ip link set v-landrop-h up
sudo ip netns exec "$SENDER_NS" ip link set v-landrop-s up
sudo ip netns exec "$SENDER_NS" ping -c 1 -W 1 "$HOST_IP" >/dev/null

printf 'LanDrop remote E2E across %s -> %s\n' "$SENDER_IP" "$HOST_IP" >"$PAYLOAD"
chmod 666 "$PAYLOAD" || true

echo "==> Starting host device in namespace $HOST_NS ($HOST_IP)"
: >"$HOST_LOG"
chmod 666 "$HOST_LOG"
sudo ip netns exec "$HOST_NS" stdbuf -oL -eL "$BIN" host \
  --bind "$HOST_IP" \
  --port "$PORT" \
  --invite "$INVITE" \
  --name "Host PC" \
  --tls-sessions 2 >"$HOST_LOG" 2>&1 &
HOST_PID=$!

for _ in $(seq 1 100); do
  if [[ -s "$INVITE" ]]; then
    break
  fi
  if ! kill -0 "$HOST_PID" 2>/dev/null; then
    echo "Host process exited before publishing invite"
    cat "$HOST_LOG" || true
    exit 1
  fi
  sleep 0.1
done
if [[ ! -s "$INVITE" ]]; then
  echo "Host did not publish an invite"
  cat "$HOST_LOG" || true
  exit 1
fi
sudo chmod 666 "$INVITE" 2>/dev/null || true
sudo chmod 666 "$RECEIVED" 2>/dev/null || true

echo "==> Starting sender device in namespace $SENDER_NS ($SENDER_IP)"
: >"$SENDER_LOG"
chmod 666 "$SENDER_LOG"
set +e
sudo ip netns exec "$SENDER_NS" stdbuf -oL -eL "$BIN" sender \
  --invite "$INVITE" \
  --preflight \
  --expect-name "Host PC" >"$SENDER_LOG" 2>&1
SENDER_STATUS=$?
wait "$HOST_PID"
HOST_STATUS=$?
set -e

echo "==> Host log"
cat "$HOST_LOG"
echo "==> Sender log"
cat "$SENDER_LOG"

if [[ "$SENDER_STATUS" -ne 0 || "$HOST_STATUS" -ne 0 ]]; then
  echo "E2E FAILED (host=$HOST_STATUS sender=$SENDER_STATUS)"
  exit 1
fi

if ! grep -q "SENDER PASS" "$SENDER_LOG"; then
  echo "E2E FAILED: sender did not pass"
  exit 1
fi

echo "E2E PASS: two virtual devices probed over remote TLS invite (${SENDER_IP} -> ${HOST_IP}:${PORT})"
