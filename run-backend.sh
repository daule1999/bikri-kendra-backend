#!/usr/bin/env bash
set -euo pipefail

# Usage: ./run-backend.sh                         -> pull & run :main
#        ./run-backend.sh <tag>                    -> pull & run a specific tag (e.g. main-798abeb, v1.2.3)
#        ./run-backend.sh --debug                  -> also opens JDWP on :5005 for VS Code "Attach" (see .vscode/launch.json)
#        ./run-backend.sh --tunnel                 -> start cloudflared tunnel after container is up
#        ./run-backend.sh --logs                   -> tail container logs after startup
#        ./run-backend.sh <tag> --debug --tunnel --logs  -> all flags, any order
IMAGE="ghcr.io/daule1999/vy/bikri-backend"
TAG="main"
DEBUG_ARGS=()
TUNNEL=false
SHOW_LOGS=false

for arg in "$@"; do
  case "$arg" in
    --debug)
      echo "==> Debug mode: JDWP will listen on 5005"
      DEBUG_ARGS=(-p 5005:5005 -e "JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
      ;;
    --tunnel)
      TUNNEL=true
      ;;
    --logs)
      SHOW_LOGS=true
      ;;
    *)
      TAG="$arg"
      ;;
  esac
done

echo "==> Deploying tag: $TAG"

# 0. Stop & remove any previous backend container FIRST, before touching infra —
#    otherwise a leftover container can hold a port that traefik/redis/zipkin need.
echo "==> Stopping old bikri-backend container (if any)"
docker stop bikri-backend >/dev/null 2>&1 || true
docker rm -f bikri-backend >/dev/null 2>&1 || true

echo "==> Killing any leftover cloudflared processes"
pkill -f "cloudflared tunnel" 2>/dev/null || true

# 1. Start infra (redis, traefik, zipkin) from bikri-kendra, if not already running.
#    Run this script from anywhere; it cd's into the traefik folder itself.
TRAEFIK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../bikri-kendra/traefik" 2>/dev/null && pwd || echo "")"

if [ -z "$TRAEFIK_DIR" ]; then
  # Fallback: hardcode the known path.
  TRAEFIK_DIR="$HOME/Downloads/Workspace/Event_Manage/bikri-kendra/traefik"
fi

echo "==> Starting infra (redis, traefik, zipkin) from $TRAEFIK_DIR"
(
  cd "$TRAEFIK_DIR"
  docker compose --env-file .env.local \
    -f docker-compose.yml \
    -f docker-compose.local.yml \
    up -d redis traefik zipkin
)

# Compose project name defaults to the folder name ("traefik"), so the network is:
NETWORK="traefik_gateway-network"

# Make sure the network exists (in case compose named it differently on this machine).
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "!! Expected network '$NETWORK' not found. Run 'docker network ls' and update this script."
  exit 1
fi

# 1b. Pull DB/JWT config from the same .env.local used by the infra compose files,
#     instead of hardcoding it here.
ENV_FILE="$TRAEFIK_DIR/.env.local"
if [ -f "$ENV_FILE" ]; then
  echo "==> Loading config from $ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
else
  echo "!! $ENV_FILE not found, falling back to defaults"
fi

DB_HOST="${DB_HOST:-host.docker.internal}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bikri_db}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
JWT_SECRET="${JWT_SECRET:-SadguruSadafalDeoJiMaharaj_SadguruSadafalDeoJiMaharaj_SadguruSadafalDeoJiMaharaj}"
JWT_ACCESS_TOKEN_TTL_MS="${JWT_ACCESS_TOKEN_TTL_MS:-10800000}"   # 3 hrs
JWT_REFRESH_TOKEN_TTL_SEC="${JWT_REFRESH_TOKEN_TTL_SEC:-604800}" # 7 days
AUTH_SESSION_STORE_ENABLED="${AUTH_SESSION_STORE_ENABLED:-false}"
AUTH_SESSION_CLEANUP_CRON="${AUTH_SESSION_CLEANUP_CRON:-0 0 2 * * *}"
AUTH_SESSION_CLEANUP_GRACE_HOURS="${AUTH_SESSION_CLEANUP_GRACE_HOURS:-24}"

# 2. Pull latest image. The image is only published for linux/amd64 (see build.yml),
#    so force that platform explicitly — otherwise Docker on Apple Silicon (arm64)
#    tries to pull a nonexistent arm64 manifest and fails.
echo "==> Pulling image (linux/amd64): $IMAGE:$TAG"
docker pull --platform linux/amd64 "$IMAGE:$TAG"

# 3. Run backend, attached to the same network as redis/traefik/zipkin so it can
#    reach them by container name instead of host.docker.internal.
#    NOTE: host port 8081 (not 8080) — traefik's own compose config already publishes
#    8080:8080 for its dashboard, so 8080 is reserved for traefik on this machine.
#    Reach the backend directly at localhost:8081, or through traefik at :8090.
BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-8081}"
echo "==> Starting bikri-backend on network $NETWORK (host port $BACKEND_HOST_PORT -> container 8080)"
docker run -d -p "${BACKEND_HOST_PORT}:8080" \
  --platform linux/amd64 \
  --name bikri-backend \
  --network "$NETWORK" \
  --add-host host.docker.internal:host-gateway \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
  -e SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  -e SPRING_R2DBC_URL="r2dbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e SPRING_R2DBC_USERNAME="${DB_USERNAME}" \
  -e SPRING_R2DBC_PASSWORD="${DB_PASSWORD}" \
  -e SPRING_FLYWAY_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -e JWT_SECRET="${JWT_SECRET}" \
  -e JWT_ACCESS_TOKEN_TTL_MS="${JWT_ACCESS_TOKEN_TTL_MS}" \
  -e JWT_REFRESH_TOKEN_TTL_SEC="${JWT_REFRESH_TOKEN_TTL_SEC}" \
  -e AUTH_SESSION_STORE_ENABLED="${AUTH_SESSION_STORE_ENABLED}" \
  -e AUTH_SESSION_CLEANUP_CRON="${AUTH_SESSION_CLEANUP_CRON}" \
  -e AUTH_SESSION_CLEANUP_GRACE_HOURS="${AUTH_SESSION_CLEANUP_GRACE_HOURS}" \
  -e SPRING_REDIS_HOST=redis \
  -e SPRING_ZIPKIN_BASE_URL=http://zipkin:9411 \
  ${DEBUG_ARGS[@]+"${DEBUG_ARGS[@]}"} \
  "$IMAGE:$TAG"

if [ "$TUNNEL" = true ]; then
  echo "==> Starting cloudflared tunnel -> http://localhost:${BACKEND_HOST_PORT}"
  
  # Ensure any dead tunnel processes are cleaned up first
  pkill -f "cloudflared tunnel" 2>/dev/null || true
  
  # Start an anonymous tunnel in the background
  cloudflared tunnel --url "http://localhost:${BACKEND_HOST_PORT}" &
fi

echo "==> Done. Container is running."
if [ "$SHOW_LOGS" = true ]; then
  echo "==> Tailing logs (Ctrl+C to stop tailing, container keeps running)"
  docker logs -f bikri-backend
fi
