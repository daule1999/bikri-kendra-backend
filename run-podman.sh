#!/usr/bin/env bash
set -euo pipefail

# Usage: ./run-podman.sh                          -> start infra + pull & run :main
#        ./run-podman.sh <tag>                     -> pull & run a specific tag (e.g. main-798abeb, v1.2.3)
#        ./run-podman.sh --debug                   -> also opens JDWP on :5005
#        ./run-podman.sh --tunnel                  -> start cloudflared tunnel after container is up
#        ./run-podman.sh --logs                    -> tail container logs after startup
#        ./run-podman.sh --stop                    -> stop all bikri services (infra + backend)
#        ./run-podman.sh --status                  -> show status of all services
#        ./run-podman.sh <tag> --debug --logs      -> all flags, any order
#
# Podman equivalent of run-backend.sh.
# Infra (redis, traefik, zipkin) runs as Quadlet systemd units inside the Podman Machine VM.
# Backend runs as a podman container on the same network.
# MySQL runs natively on macOS — containers reach it via the Mac's LAN IP.

# ── Config ────────────────────────────────────────────────────────────────────
IMAGE="ghcr.io/daule1999/vy/bikri-backend"
TAG="main"
DEBUG_ARGS=()
TUNNEL=false
SHOW_LOGS=false
STOP=false
STATUS=false
VM_NAME="bikri-vm"
PODMAN="/opt/podman/bin/podman"
BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-8081}"
BIKRI_ENV="$HOME/opt/bikri/secrets/app.env"

# ── Parse args ────────────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --debug)
      echo "==> Debug mode: JDWP will listen on 5005"
      DEBUG_ARGS=(-p 5005:5005 -e "JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
      ;;
    --tunnel)  TUNNEL=true  ;;
    --logs)    SHOW_LOGS=true ;;
    --stop)    STOP=true ;;
    --status)  STATUS=true ;;
    *) TAG="$arg" ;;
  esac
done

# ── Helpers ───────────────────────────────────────────────────────────────────
ssh_vm() { $PODMAN machine ssh "$VM_NAME" "$@"; }

svc_active() {
  ssh_vm "systemctl --user is-active ${1}.service 2>/dev/null" | grep -q "^active$"
}

start_svc() {
  local svc=$1
  if svc_active "$svc"; then
    echo "==> $svc already running ✓"
  else
    echo "==> Starting $svc..."
    ssh_vm "systemctl --user start ${svc}.service"
  fi
}

# ── --status ─────────────────────────────────────────────────────────────────
if [ "$STATUS" = true ]; then
  echo "==> VM:"
  $PODMAN machine list
  echo ""
  echo "==> Services:"
  ssh_vm "systemctl --user is-active redis traefik bikri-backend nginx-ui bikri-kendra-ui zipkin 2>/dev/null" \
    | paste - - - - - - | awk 'BEGIN{OFS="\t"} {print "redis="$1, "traefik="$2, "backend="$3, "nginx-ui="$4, "ui="$5, "zipkin="$6}'
  echo ""
  echo "==> Containers:"
  $PODMAN ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
  echo ""
  echo "==> Health:"
  echo -n "  Backend : "; curl -sf http://localhost:8081/actuator/health | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" 2>/dev/null || echo "not responding"
  echo -n "  Traefik : "; curl -sf -o /dev/null -w "HTTP %{http_code}" http://localhost:8090/ 2>/dev/null || echo "not responding"
  echo -n "  UI      : "; curl -sf -o /dev/null -w "HTTP %{http_code}" http://localhost:3000 2>/dev/null || echo "not responding"
  echo -n "  Zipkin  : "; curl -sf -o /dev/null -w "HTTP %{http_code}" http://localhost:9411/ 2>/dev/null || echo "not responding"
  echo ""
  exit 0
fi

# ── --stop ────────────────────────────────────────────────────────────────────
if [ "$STOP" = true ]; then
  echo "==> Stopping bikri-backend container..."
  $PODMAN stop bikri-backend 2>/dev/null || true
  $PODMAN rm -f bikri-backend 2>/dev/null || true

  echo "==> Stopping infra services..."
  ssh_vm "systemctl --user stop bikri-backend.service nginx-ui.service bikri-kendra-ui.service traefik.service redis.service zipkin.service 2>/dev/null || true"

  echo "==> Killing cloudflared..."
  pkill -f "cloudflared tunnel" 2>/dev/null || true

  echo "==> All services stopped."
  exit 0
fi

# ── Check VM is running ───────────────────────────────────────────────────────
echo "==> Deploying tag: $TAG"

VM_RUNNING=$($PODMAN machine list --format '{{.Name}} {{.Running}}' | grep "^${VM_NAME}" | awk '{print $2}' || echo "false")
if [ "$VM_RUNNING" != "true" ]; then
  echo "==> Starting Podman Machine VM ($VM_NAME)..."
  $PODMAN machine start "$VM_NAME"
  sleep 3
fi

# ── Detect Mac IP ─────────────────────────────────────────────────────────────
MAC_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "")
if [ -z "$MAC_IP" ]; then
  echo "!! Could not detect Mac LAN IP. Are you connected to a network?"
  exit 1
fi
echo "==> Mac IP: $MAC_IP"

# ── Check if IP changed since last run ───────────────────────────────────────
if [ -f "$BIKRI_ENV" ]; then
  STORED_IP=$(grep "^DB_HOST=" "$BIKRI_ENV" | cut -d= -f2 || echo "")
  if [ -n "$STORED_IP" ] && [ "$STORED_IP" != "$MAC_IP" ]; then
    echo "==> Mac IP changed: $STORED_IP -> $MAC_IP. Updating unit files..."
    ssh_vm "
      sed -i 's|AddHost=host.docker.internal:.*|AddHost=host.docker.internal:${MAC_IP}|g' \
        ~/.config/containers/systemd/traefik.container \
        ~/.config/containers/systemd/bikri-backend.container 2>/dev/null || true
      sed -i 's|Environment=SPRING_DATASOURCE_URL=.*|Environment=SPRING_DATASOURCE_URL=jdbc:mysql://${MAC_IP}:3306/bikri_db|' \
        ~/.config/containers/systemd/bikri-backend.container 2>/dev/null || true
      sed -i 's|Environment=SPRING_R2DBC_URL=.*|Environment=SPRING_R2DBC_URL=r2dbc:mysql://${MAC_IP}:3306/bikri_db|' \
        ~/.config/containers/systemd/bikri-backend.container 2>/dev/null || true
      sed -i 's|Environment=SPRING_FLYWAY_URL=.*|Environment=SPRING_FLYWAY_URL=jdbc:mysql://${MAC_IP}:3306/bikri_db|' \
        ~/.config/containers/systemd/bikri-backend.container 2>/dev/null || true
      systemctl --user daemon-reload
    "
    sed -i '' "s|^DB_HOST=.*|DB_HOST=${MAC_IP}|" "$BIKRI_ENV"
    echo "==> IP updated ✓"
  fi
fi

# ── Load env ──────────────────────────────────────────────────────────────────
if [ -f "$BIKRI_ENV" ]; then
  echo "==> Loading config from $BIKRI_ENV"
  set -a
  # shellcheck disable=SC1090
  source "$BIKRI_ENV"
  set +a
else
  echo "!! $BIKRI_ENV not found. Run bikri-bootstrap.sh first."
  exit 1
fi

DB_HOST="${DB_HOST:-$MAC_IP}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bikri_db}"
DB_USERNAME="${DB_USERNAME:-bikri_app}"
DB_PASSWORD="${DB_PASSWORD:-}"
JWT_SECRET="${JWT_SECRET:-SadguruSadafalDeoJiMaharaj_SadguruSadafalDeoJiMaharaj_SadguruSadafalDeoJiMaharaj}"
JWT_ACCESS_TOKEN_TTL_MS="${JWT_ACCESS_TOKEN_TTL_MS:-10800000}"
JWT_REFRESH_TOKEN_TTL_SEC="${JWT_REFRESH_TOKEN_TTL_SEC:-604800}"
AUTH_SESSION_STORE_ENABLED="${AUTH_SESSION_STORE_ENABLED:-false}"
AUTH_SESSION_CLEANUP_CRON="${AUTH_SESSION_CLEANUP_CRON:-0 0 2 * * *}"
AUTH_SESSION_CLEANUP_GRACE_HOURS="${AUTH_SESSION_CLEANUP_GRACE_HOURS:-24}"

# ── Stop old backend container ────────────────────────────────────────────────
echo "==> Stopping old bikri-backend container (if any)"
$PODMAN stop bikri-backend 2>/dev/null || true
$PODMAN rm -f bikri-backend 2>/dev/null || true

echo "==> Killing any leftover cloudflared processes"
pkill -f "cloudflared tunnel" 2>/dev/null || true

# ── Start infra services ──────────────────────────────────────────────────────
echo "==> Starting infra services (redis, traefik, zipkin)..."
ssh_vm "systemctl --user daemon-reload 2>/dev/null || true"

start_svc redis
start_svc traefik
start_svc zipkin

# Wait for redis to be ready
echo "==> Waiting for Redis..."
for i in $(seq 1 10); do
  $PODMAN exec redis redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 1
done
$PODMAN exec redis redis-cli ping 2>/dev/null | grep -q PONG && echo "==> Redis ready ✓" || echo "!! Redis not responding"

# ── Pull backend image ────────────────────────────────────────────────────────
echo "==> Pulling image (linux/amd64): $IMAGE:$TAG"
$PODMAN pull --platform linux/amd64 "$IMAGE:$TAG"

# ── Run backend container ─────────────────────────────────────────────────────
# Network: bikri-net (same network as redis/traefik/zipkin Quadlet containers)
# The Quadlet network is named "systemd-bikri-net" by Podman
NETWORK="systemd-bikri-net"

if ! $PODMAN network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "!! Network '$NETWORK' not found."
  echo "   Make sure infra services have been started at least once."
  echo "   Run: podman machine ssh $VM_NAME 'systemctl --user start redis.service'"
  exit 1
fi

echo "==> Starting bikri-backend (host port $BACKEND_HOST_PORT -> container 8080)"
$PODMAN run -d \
  -p "${BACKEND_HOST_PORT}:8080" \
  --platform linux/amd64 \
  --name bikri-backend \
  --network "$NETWORK" \
  --add-host "host.docker.internal:${MAC_IP}" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
  -e SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  -e SPRING_R2DBC_URL="r2dbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e SPRING_R2DBC_USERNAME="${DB_USERNAME}" \
  -e SPRING_R2DBC_PASSWORD="${DB_PASSWORD}" \
  -e SPRING_FLYWAY_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e SPRING_FLYWAY_USER="${DB_USERNAME}" \
  -e SPRING_FLYWAY_PASSWORD="${DB_PASSWORD}" \
  -e JWT_SECRET="${JWT_SECRET}" \
  -e JWT_ACCESS_TOKEN_TTL_MS="${JWT_ACCESS_TOKEN_TTL_MS}" \
  -e JWT_REFRESH_TOKEN_TTL_SEC="${JWT_REFRESH_TOKEN_TTL_SEC}" \
  -e AUTH_SESSION_STORE_ENABLED="${AUTH_SESSION_STORE_ENABLED}" \
  -e AUTH_SESSION_CLEANUP_CRON="${AUTH_SESSION_CLEANUP_CRON}" \
  -e AUTH_SESSION_CLEANUP_GRACE_HOURS="${AUTH_SESSION_CLEANUP_GRACE_HOURS}" \
  -e SPRING_REDIS_HOST=redis \
  -e SPRING_REDIS_PORT=6379 \
  -e SPRING_ZIPKIN_BASE_URL=http://zipkin:9411 \
  -e SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=30000 \
  -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10 \
  -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=2 \
  -e SPRING_DATASOURCE_HIKARI_CONNECTION_TEST_QUERY="SELECT 1" \
  ${DEBUG_ARGS[@]+"${DEBUG_ARGS[@]}"} \
  "$IMAGE:$TAG"

# ── Tunnel ────────────────────────────────────────────────────────────────────
if [ "$TUNNEL" = true ]; then
  echo "==> Starting cloudflared tunnel -> http://localhost:${BACKEND_HOST_PORT}"
  pkill -f "cloudflared tunnel" 2>/dev/null || true
  cloudflared tunnel --url "http://localhost:${BACKEND_HOST_PORT}" &
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "==> Done. bikri-backend is starting up."
echo "    Direct:  http://localhost:${BACKEND_HOST_PORT}/actuator/health"
echo "    Traefik: http://localhost:8090/api/..."
echo ""

if [ "$SHOW_LOGS" = true ]; then
  echo "==> Tailing logs (Ctrl+C to stop tailing, container keeps running)"
  $PODMAN logs -f bikri-backend
fi
