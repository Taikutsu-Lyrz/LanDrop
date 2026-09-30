#!/usr/bin/env bash
# Two-device E2E where the invite advertises a public WAN endpoint while the host
# listens on a private LAN address behind DNAT (simulates router port-forwarding).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BIN="$ROOT/tools/remote-e2e/target/debug/landrop-remote-e2e"
RUN_DIR="$ROOT/validation/runs/remote-public-e2e"
INVITE="$RUN_DIR/invite.txt"
HOST_LOG="$RUN_DIR/host.log"
SENDER_LOG="$RUN_DIR/sender.log"

HOST_NS="landrop-pub-host"
SENDER_NS="landrop-pub-sender"
WAN_NS="landrop-pub-wan"

PUBLIC_IP="203.0.113.50"
HOST_LAN_IP="192.168.77.10"
ROUTER_LAN_IP="192.168.77.1"
SENDER_IP="10.55.0.2"
ROUTER_SENDER_IP="10.55.0.1"
PORT="50502"

cleanup() {
  if [[ -n "${HOST_PID:-}" ]]; then
    sudo kill "$HOST_PID" 2>/dev/null || true
    wait "$HOST_PID" 2>/dev/null || true
  fi
  sudo ip netns del "$HOST_NS" 2>/dev/null || true
  sudo ip netns del "$SENDER_NS" 2>/dev/null || true
  sudo ip netns del "$WAN_NS" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$RUN_DIR"
chmod 777 "$RUN_DIR"
rm -f "$INVITE" "$HOST_LOG" "$SENDER_LOG"

echo "==> Building remote E2E harness"
cargo +nightly build --manifest-path "$ROOT/tools/remote-e2e/Cargo.toml"
test -x "$BIN"

echo "==> Creating WAN router + LAN host + remote sender namespaces"
sudo ip netns del "$HOST_NS" 2>/dev/null || true
sudo ip netns del "$SENDER_NS" 2>/dev/null || true
sudo ip netns del "$WAN_NS" 2>/dev/null || true
sudo ip netns add "$HOST_NS"
sudo ip netns add "$SENDER_NS"
sudo ip netns add "$WAN_NS"

sudo ip link add v-pub-host type veth peer name v-wan-lan
sudo ip link add v-pub-sender type veth peer name v-wan-sender
sudo ip link set v-pub-host netns "$HOST_NS"
sudo ip link set v-wan-lan netns "$WAN_NS"
sudo ip link set v-pub-sender netns "$SENDER_NS"
sudo ip link set v-wan-sender netns "$WAN_NS"

sudo ip netns exec "$HOST_NS" ip addr add "${HOST_LAN_IP}/24" dev v-pub-host
sudo ip netns exec "$WAN_NS" ip addr add "${ROUTER_LAN_IP}/24" dev v-wan-lan
sudo ip netns exec "$WAN_NS" ip addr add "${ROUTER_SENDER_IP}/24" dev v-wan-sender
sudo ip netns exec "$WAN_NS" ip addr add "${PUBLIC_IP}/32" dev v-wan-sender
sudo ip netns exec "$SENDER_NS" ip addr add "${SENDER_IP}/24" dev v-pub-sender

for ns in "$HOST_NS" "$SENDER_NS" "$WAN_NS"; do
  sudo ip netns exec "$ns" ip link set lo up
done
sudo ip netns exec "$HOST_NS" ip link set v-pub-host up
sudo ip netns exec "$WAN_NS" ip link set v-wan-lan up
sudo ip netns exec "$WAN_NS" ip link set v-wan-sender up
sudo ip netns exec "$SENDER_NS" ip link set v-pub-sender up

sudo ip netns exec "$HOST_NS" ip route add default via "$ROUTER_LAN_IP"
sudo ip netns exec "$SENDER_NS" ip route add "${PUBLIC_IP}/32" via "$ROUTER_SENDER_IP"

sudo ip netns exec "$WAN_NS" sysctl -w net.ipv4.ip_forward=1 >/dev/null
sudo ip netns exec "$WAN_NS" iptables -t nat -A PREROUTING -d "$PUBLIC_IP" -p tcp --dport "$PORT" -j DNAT --to-destination "${HOST_LAN_IP}:${PORT}"
sudo ip netns exec "$WAN_NS" iptables -t nat -A POSTROUTING -s "${HOST_LAN_IP}/32" -j MASQUERADE
sudo ip netns exec "$WAN_NS" iptables -A FORWARD -i v-wan-sender -o v-wan-lan -j ACCEPT
sudo ip netns exec "$WAN_NS" iptables -A FORWARD -i v-wan-lan -o v-wan-sender -j ACCEPT

sudo ip netns exec "$SENDER_NS" ping -c 1 -W 1 "$PUBLIC_IP" >/dev/null

echo "==> Starting host on private LAN ($HOST_LAN_IP) with public invite endpoint ($PUBLIC_IP)"
: >"$HOST_LOG"
chmod 666 "$HOST_LOG"
sudo ip netns exec "$HOST_NS" stdbuf -oL -eL "$BIN" host \
  --bind "$HOST_LAN_IP" \
  --port "$PORT" \
  --public-host "$PUBLIC_IP" \
  --invite "$INVITE" \
  --name "Public Host PC" \
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
if ! grep -q "endpoint=${PUBLIC_IP}:${PORT}" "$HOST_LOG"; then
  echo "Host did not advertise public endpoint ${PUBLIC_IP}:${PORT}"
  cat "$HOST_LOG" || true
  exit 1
fi
sudo chmod 666 "$INVITE" 2>/dev/null || true

echo "==> Sender connects using public endpoint from invite"
: >"$SENDER_LOG"
chmod 666 "$SENDER_LOG"
set +e
sudo ip netns exec "$SENDER_NS" stdbuf -oL -eL "$BIN" sender \
  --invite "$INVITE" \
  --preflight \
  --expect-name "Public Host PC" >"$SENDER_LOG" 2>&1
SENDER_STATUS=$?
wait "$HOST_PID"
HOST_STATUS=$?
set -e

echo "==> Host log"
cat "$HOST_LOG"
echo "==> Sender log"
cat "$SENDER_LOG"

if [[ "$SENDER_STATUS" -ne 0 || "$HOST_STATUS" -ne 0 ]]; then
  echo "PUBLIC E2E FAILED (host=$HOST_STATUS sender=$SENDER_STATUS)"
  exit 1
fi

if ! grep -q "SENDER PASS" "$SENDER_LOG"; then
  echo "PUBLIC E2E FAILED: sender did not pass"
  exit 1
fi

echo "PUBLIC E2E PASS: sender reached host via public invite endpoint ${PUBLIC_IP}:${PORT}"
