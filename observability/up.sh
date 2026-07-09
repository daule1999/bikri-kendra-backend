#!/usr/bin/env bash
# Start (or stop) the observability stack on EITHER Docker or Podman —
# detects the runtime and adjusts network name + socket automatically.
#
#   ./up.sh              # start (auto-detect runtime)
#   ./up.sh down         # stop
#   RUNTIME=podman ./up.sh          # force runtime
#   OBS_ATTACH_NETWORK=my-net ./up.sh   # override infra network name
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

ACTION="${1:-up}"

# ── MySQL exporter credentials ───────────────────────────────────────────────
# Reuse the same .env.local the infra/deploy scripts use, so the exporter logs
# in with the SAME user as the backend (DB_USERNAME/DB_PASSWORD). Explicit
# MYSQL_EXPORTER_USER/PASSWORD env vars still win if set.
ENV_FILE="${ENV_FILE:-$HOME/Downloads/Workspace/Event_Manage/bikri-kendra/traefik/.env.local}"
if [ -f "$ENV_FILE" ]; then
  set -a; # shellcheck disable=SC1090
  source "$ENV_FILE"; set +a
  echo "==> Loaded DB config from $ENV_FILE"
fi
export MYSQL_EXPORTER_USER="${MYSQL_EXPORTER_USER:-${DB_USERNAME:-root}}"
export MYSQL_EXPORTER_PASSWORD="${MYSQL_EXPORTER_PASSWORD:-${DB_PASSWORD:-}}"
[ -n "${DB_HOST:-}" ] && export MYSQL_HOST="${MYSQL_HOST:-$DB_HOST}"
[ -n "${DB_PORT:-}" ] && export MYSQL_PORT="${MYSQL_PORT:-$DB_PORT}"

# ── Detect runtime ────────────────────────────────────────────────────────────
if [ -z "${RUNTIME:-}" ]; then
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    RUNTIME=docker
  elif command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    RUNTIME=podman
  else
    echo "!! Neither a running Docker daemon nor Podman found." >&2
    exit 1
  fi
fi
echo "==> Runtime: $RUNTIME"

# ── Runtime-specific settings ────────────────────────────────────────────────
if [ "$RUNTIME" = "podman" ]; then
  # Quadlet infra network (redis/traefik/zipkin) + docker-compatible API socket
  export OBS_ATTACH_NETWORK="${OBS_ATTACH_NETWORK:-systemd-bikri-net}"
  PODMAN_SOCK="${PODMAN_SOCK:-$(podman info --format '{{.Host.RemoteSocket.Path}}' 2>/dev/null || true)}"
  PODMAN_SOCK="${PODMAN_SOCK#unix://}"   # strip scheme — volume mounts need a bare path
  if [ -z "$PODMAN_SOCK" ]; then
    echo "!! Could not detect podman socket. Enable it first:" >&2
    echo "   systemctl --user enable --now podman.socket   (inside the podman machine VM on macOS)" >&2
    exit 1
  fi
  export PODMAN_SOCK
  # cAdvisor: /var/lib/docker doesn't exist in the podman VM → mount the podman storage dir instead
  export CADVISOR_STORAGE_DIR="${CADVISOR_STORAGE_DIR:-/var/lib/containers}"
  # Podman rejects Docker's "host-gateway" in extra_hosts → use a harmless placeholder;
  # to reach the host (e.g. MySQL on the Mac) podman resolves host.containers.internal natively.
  export EXTRA_HOST_ENTRY="${EXTRA_HOST_ENTRY:-placeholder.invalid:127.0.0.1}"
  export MYSQL_HOST="${MYSQL_HOST:-host.containers.internal}"
  echo "==> Podman socket: $PODMAN_SOCK"
  COMPOSE=(podman compose -f docker-compose.yml -f docker-compose.podman.yml)
else
  export OBS_ATTACH_NETWORK="${OBS_ATTACH_NETWORK:-traefik_gateway-network}"
  export CADVISOR_STORAGE_DIR="${CADVISOR_STORAGE_DIR:-/var/lib/docker}"
  COMPOSE=(docker compose -f docker-compose.yml)
fi
echo "==> Attaching to network: $OBS_ATTACH_NETWORK"

# ── Verify the infra network exists (created by redis/traefik/zipkin infra) ──
if ! "$RUNTIME" network inspect "$OBS_ATTACH_NETWORK" >/dev/null 2>&1; then
  echo "!! Network '$OBS_ATTACH_NETWORK' not found — start the infra (redis/traefik/zipkin) first," >&2
  echo "   or override with OBS_ATTACH_NETWORK=<name> ./up.sh" >&2
  exit 1
fi

# ── Go ────────────────────────────────────────────────────────────────────────
if [ "$ACTION" = "down" ]; then
  "${COMPOSE[@]}" down
else
  "${COMPOSE[@]}" up -d
  echo "==> Grafana:    http://localhost:3001 (admin/admin)"
  echo "==> Loki:       http://localhost:3100"
  echo "==> Prometheus: http://localhost:9090"
fi
