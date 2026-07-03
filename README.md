# bikri-backend — modular monolith

The six microservices (auth, user, inventory, sales, billing, printer) as **Maven modules in one Spring Boot app**: one JVM (~1–1.5 GB instead of ~3 GB+), one port (8080), **one database (`bikri_db`)**, no gateway needed. All REST paths are unchanged (`/api/auth-svc/**`, `/api/sales-svc/**`, …) so the frontend works as-is — just point it at this app's port.

```
platform/   shared JwtUtil (auth's superset), WebClientConfig, JacksonConfig, RedisConfig, TimezoneValidationBean
auth/ user/ inventory/ sales/ billing/ printer/   ← former services, code copied by scripts/migrate.sh
app/        BikriBackendApplication (com.vy), JwtAuthWebFilter (replaces Traefik forwardAuth),
            AppSecurityConfig (single chain), MultiModuleFlywayConfig, merged application.yaml
```

## Getting to first run

```bash
# 1. (already done here) pull service code from ../bikri-kendra — re-run after upstream changes
./scripts/migrate.sh ../bikri-kendra

# 2. build — THIS IS THE REAL VERIFICATION; fix anything it reports (see Known checks below)
./mvnw clean package -DskipTests

# 3. one-time DB merge: auth_db/user_db/…/printer_db → bikri_db (backup + dry-run built in)
./scripts/merge-databases.sh --dry-run
./scripts/merge-databases.sh

# 4. run
DB_PASSWORD=... JWT_SECRET=... ./mvnw spring-boot:run -pl app
# debug: MAVEN_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" ./mvnw spring-boot:run -pl app
```

## How the hard parts were handled

| Problem | Solution |
|---|---|
| 6 databases | `merge-databases.sh`: full backup → collision check → atomic `RENAME TABLE` into `bikri_db` (no data copy) → old DBs left empty for later manual DROP |
| Flyway version collisions (every service has V1…) | migrations namespaced to `db/migration/<module>/`, each with its own history table `flyway_schema_history_<module>` (old history tables renamed by the merge script → checksums stay valid) |
| Duplicate bean classes (JwtUtil ×4, RedisConfig ×4, JacksonConfig ×5, …) | centralized once in `platform`/`app`; per-module copies deleted, imports rewritten by migrate.sh |
| `GlobalExceptionHandler` ×5, `UserServiceProperties` ×2 | renamed with module prefix (`SalesGlobalExceptionHandler`, …) — bean names now unique |
| Traefik forwardAuth + X-User-Id/X-Username/X-Roles headers | `JwtAuthWebFilter` validates the JWT in-process, enforces the same admin rules, checks Redis force-logout, injects the same X-* headers controllers already read |
| Inter-service WebClient calls | kept, pointed at `http://localhost:8080` (self-loop) — behavior identical, zero call-site changes; replace with direct bean calls module by module later |
| printer package root is `com.vy.printer` not `com.vy.sales` | main class lives in `com.vy` so default scanning covers both |

## Verification already performed (statically — no JDK 21 in the generation environment)

- every intra-project `com.vy.*` import resolves to a real file ✅
- the shared platform `JwtUtil` covers every method called on it across all modules ✅
- every `@Value("${…}")` key without a default exists in the merged `application.yaml` ✅
- all `@ConfigurationProperties` prefixes are present under `services:` ✅
- all 24 controller base paths are unique (no startup mapping collisions) ✅
- zero duplicate Spring bean class names across modules ✅
- internal service-to-service endpoints that used to be called WITHOUT a token
  (`/api/users-svc/validate` at login, `/api/sales-svc/events/exists`,
  `/api/inventory/analytics/**` for reports) are allowed through `JwtAuthWebFilter`
  **only from loopback** — otherwise login and reports would 401 on the self-loop ✅

## Known checks after `./mvnw clean package`

1. `./mvnw clean package -DskipTests` is still the authoritative gate — run it before first deploy. Residual risk is small given the checks above; the six original configs are in `migration-reference/` for diffing if a key mismatch surfaces.
2. Empty placeholder files (deleted classes the workspace couldn't physically remove — see `scripts/empty-placeholders.txt`): run `find . -name "*.java" -empty -delete` once. They are compile-safe either way.
3. Tests were not migrated (they referenced the per-service boot apps). Port the valuable ones to `app/src/test` against the monolith context.
4. Cutover: deploy this instead of the six services + Traefik backend routes; set frontend `INTERNAL_API_BASE_URL=http://<host>:8080`. Keep the old images around for one event as rollback.
5. Sales' reports still call inventory over the self-loop — the natural next refactor is replacing `ReportServiceImpl`'s WebClient joins with direct repository queries (kills the cross-service report inconsistency class entirely).

## Deploy

- Image: `Dockerfile` (multi-arch via `.github/workflows/build-push.yml` → `ghcr.io/<org>/vy/bikri-backend`).
- GitOps: `bikri-gitops/apps/backend-monolith/` — one Deployment replaces the six.
- Sizing: `-XX:MaxRAMPercentage=70` is set; give the container ~1.5–2 GB.
