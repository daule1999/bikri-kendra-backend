# Observability — logs, metrics, traces

Self-hosted Grafana stack that stores **every API request/response** (frontend and backend), plus metrics and distributed traces.

## Architecture

```
Browser (lib/logger.ts + apiClient X-Request-Id)
   └─ batched POST /api/logs ──► Next.js route ──► Loki
Next.js server (stdout) ─────────► Alloy (docker logs) ──► Loki
Spring Boot (stdout JSON) ───────► Alloy (docker logs) ──► Loki
Spring Boot /actuator/prometheus ◄── Prometheus (scrape)
Spring Boot spans (Brave/Zipkin) ──► Tempo :9411
                                       ▲
Grafana :3001 ── queries Loki + Prometheus + Tempo
```

- **Loki** — log storage (retention: `LOKI_RETENTION`, default `336h` = 14 days)
- **Prometheus** — metrics (retention: `PROMETHEUS_RETENTION`, default `30d`)
- **Tempo** — traces (retention: `TEMPO_RETENTION`, default `336h`)
- **Alloy** — tails stdout/stderr of *all* docker containers on the host, ships to Loki labeled by container name
- **Grafana** — UI at http://localhost:3001 (admin/admin), datasources pre-provisioned with log↔trace click-through
- **node-exporter** — host CPU / RAM / disk / load
- **cAdvisor** — per-container CPU / RAM / network
- **mysqld-exporter** — MySQL connections, QPS, InnoDB row locks, buffer pool, slow queries

## Dashboards (auto-provisioned)

Four dashboards appear in Grafana on first start:

| Dashboard | Shows |
|---|---|
| **Backend — Spring Boot** | RPS by endpoint, latency p50/p95/p99, 4xx/5xx error rate, JVM heap, process CPU, GC pauses, threads, R2DBC pool |
| **Database — MySQL** | up-status, connections vs max, QPS + slow queries, InnoDB row-lock waits & lock time, table locks, buffer pool, aborted connections, network |
| **Frontend — Next.js** | container CPU/RAM, browser API call rate & failures, browser-observed latency p50/p95 (from shipped logs), error-log rate, server log volume |
| **Infrastructure — Host & Containers** | host CPU/RAM/disk/load, per-container CPU/RAM/network, Loki ingest self-check |

Latency percentiles need histogram buckets — enabled in `application.yaml`
(`management.metrics.distribution.percentiles-histogram.http.server.requests: true`), so
redeploy the backend once for the p50/p95/p99 panels to populate.

### MySQL exporter credentials

`up.sh` sources the infra `.env.local` (same file the deploy scripts use, path
overridable via `ENV_FILE=...`) and logs the exporter in with the **backend's own DB
user** (`DB_USERNAME`/`DB_PASSWORD`, host from `DB_HOST`/`DB_PORT`). No separate user
needed. Override explicitly with `MYSQL_EXPORTER_USER` / `MYSQL_EXPORTER_PASSWORD` /
`MYSQL_HOST` / `MYSQL_PORT` if desired — e.g. a least-privilege user
(`GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.*`) for production.

Exporter caveats: on Docker Desktop / podman machine, node-exporter reports the Linux VM
(that's where containers actually run). cAdvisor works best on Docker; on Podman its data
can be partial — also mount `/var/lib/containers` there if container panels come up empty.

## Start

```bash
cd observability
./up.sh          # auto-detects Docker or Podman and adjusts network + socket
./up.sh down     # stop the stack
```

`up.sh` picks the runtime (force with `RUNTIME=podman ./up.sh`) and sets the right
defaults: Docker → `traefik_gateway-network`; Podman → `systemd-bikri-net` plus the
`docker-compose.podman.yml` override, which mounts Podman's docker-compatible API
socket where Alloy expects it (auto-detected via `podman info`, override with
`PODMAN_SOCK=...`). Manual equivalents:

```bash
docker compose up -d                                              # Docker
OBS_ATTACH_NETWORK=systemd-bikri-net PODMAN_SOCK=/run/user/1000/podman/podman.sock \
  podman compose -f docker-compose.yml -f docker-compose.podman.yml up -d   # Podman
```

Retention is env-configurable at startup (set alongside the vars above, or in a `.env`
file next to the compose file): `LOKI_RETENTION=336h` (logs), `TEMPO_RETENTION=336h`
(traces), `PROMETHEUS_RETENTION=30d` (metrics). Loki/Tempo use Go durations in hours
(`720h` = 30 days); changing them requires only a `docker compose up -d` restart.

The stack attaches to the **same network as redis/traefik/zipkin** (external, from the
`bikri-kendra/traefik` compose), so everything resolves by container name: the backend
sends spans to `tempo:9411`, Prometheus scrapes `bikri-backend:8080`, the frontend
container pushes logs to `loki:3100`. Tempo speaks the Zipkin protocol on 9411 —
it replaces the standalone `zipkin` container (which you can retire) — but that port
is intentionally not published to the host to avoid clashing with it.

## Wire up the backend

Run the backend with:

| Env var | Value | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `json-logs` | JSON console logs (Loki-friendly). Omit locally for readable text. |
| `ZIPKIN_ENDPOINT` | `http://tempo:9411/api/v2/spans` | Span export to Tempo (same network). Use `http://localhost:9411/...` only when running the backend on the host against the old zipkin. |
| `API_LOGGING_ENABLED` | `true` (default) | The req/res capture filter (`platform/.../logging/ApiLoggingWebFilter`) |
| `API_LOGGING_BODIES` | `true` (default) | Set `false` to log only metadata, no bodies |
| `API_LOGGING_MAX_BODY_BYTES` | `10240` (default) | Truncation limit per body |
| `TRACING_SAMPLE_RATE` | `1.0` (default) | Lower to 0.1 under heavy load |

`run-backend.sh` now sets these automatically (json-logs profile + `tempo:9411` endpoint).
Prometheus scrapes `bikri-backend:8080/actuator/prometheus` by container name — if you run the backend on the host instead, change the target in `prometheus/prometheus.yml` to `host.docker.internal:8081`.

## Wire up the frontend

| Env var | Value | Why |
|---|---|---|
| `LOKI_PUSH_URL` | `http://loki:3100/loki/api/v1/push` when the frontend container is on the same network; default `http://localhost:3100/...` for host-run dev | Where `/api/logs` forwards browser logs |
| `NEXT_PUBLIC_LOG_SHIPPING` | unset (on) / `false` | Kill switch for browser log shipping |

Next.js *server* logs need no config — Alloy picks up container stdout automatically.

## Accessing logs on Podman / non-Docker runtimes

**Viewing is identical everywhere** — Grafana at http://localhost:3001 (podman machine
forwards published ports to the host automatically). Only log *collection* differs,
because Alloy discovers containers through a runtime socket:

1. **Podman (recommended path)** — Podman ships a Docker-compatible API socket; the
   `docker-compose.podman.yml` override mounts it where Alloy already looks, so the
   Alloy config is unchanged. One-time setup inside the VM:
   `podman machine ssh bikri -- systemctl --user enable --now podman.socket`
2. **Systemd/Quadlet units (redis, traefik, etc.)** — these also log to **journald**.
   If socket discovery misses anything, Alloy's `loki.source.journal` component can tail
   the journal instead: mount `/var/log/journal` (or `/run/log/journal`) plus
   `/etc/machine-id` into the alloy container and add that component to `config.alloy`.
3. **Any other runtime / no runtime at all** — two universal fallbacks:
   - tail log files with Alloy's `loki.source.file` (mount the log directory), or
   - push straight to Loki's HTTP API (`http://loki:3100/loki/api/v1/push`) — this is
     exactly what the frontend's `/api/logs` route does, and it works from anything
     that can make an HTTP call.

You can also query Loki without Grafana: `logcli` or plain
`curl -G localhost:3100/loki/api/v1/query_range --data-urlencode 'query={container="bikri-backend"}'`.

## Viewing any API request/response

Grafana → Explore → **Loki**:

```logql
# all backend API events, parsed
{container="bikri-backend"} | json | type="api"

# a specific endpoint, errors only
{container="bikri-backend"} | json | uri=~"/api/sales.*" | status >= 400

# find a call by the X-Request-Id echoed to the client
{container="bikri-backend"} | json | requestId="<uuid>"

# browser-side API logs
{service="eventmanagement-frontend"} |= "API_CLIENT"
```

Each backend `type="api"` event contains: `method, uri, query, status, durationMs, requestId, requestBody, responseBody, clientIp, traceId, spanId`. Click the `traceId` derived-field link to jump to the full trace in Tempo.

Correlation: the browser generates an `X-Request-Id` per call (visible in frontend logs). The backend echoes/logs it. Note: the Next.js proxy routes under `app/api/*` build headers manually and don't yet forward `X-Request-Id` — the backend then generates its own. Forwarding `request.headers.get('x-request-id')` in the proxies is an easy future improvement for end-to-end correlation.

## Privacy & disk

- Bodies are **redacted** for `password/token/secret`-style JSON fields (configurable: `observability.api-logging.redact-json-fields`) and truncated at 10KB.
- Bodies still contain business data (sales, billing). Retention is 14 days — shorten in `loki-config.yaml` if disk is tight, or set `API_LOGGING_BODIES=false` to keep only metadata.
- Frontend `apiClient` no longer logs auth headers (it previously printed the bearer token into console logs).

## Verify after first start

1. `docker compose ps` — five services healthy.
2. Hit any backend endpoint, then in Grafana Explore run `{container="bikri-backend"} | json | type="api"` — you should see the call with bodies.
3. Prometheus → Status → Targets: `bikri-backend` UP.
4. Open a trace: Explore → Tempo → Search.

## Caveats / to verify on first build

- Run `./mvnw compile` — the new filter targets Spring Boot 4 / Framework 7 APIs (`DataBuffer.readPosition`, `ServerHttp*Decorator`); this environment had no JDK 21 to compile-check.
- Property names `management.zipkin.tracing.endpoint` / `management.tracing.sampling.probability` are the Boot 3.x names; verify unchanged in Boot 4 docs if spans don't appear.
- Image tags in `docker-compose.yml` are pinned conservatively — newer versions exist; bump when convenient.
