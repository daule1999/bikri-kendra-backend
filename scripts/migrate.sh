#!/usr/bin/env bash
# =============================================================================
# migrate.sh — copies the six microservices from ../bikri-kendra into this
# modular monolith. Idempotent: wipes and re-copies module sources each run,
# so you can re-run after upstream changes until the cutover is final.
#
#   ./scripts/migrate.sh [path-to-bikri-kendra]     (default: ../bikri-kendra)
#
# What it does per module:
#   1. Copies src/main/java.
#   2. Copies Flyway migrations into db/migration/<module>/ (namespaced).
#   3. Deletes classes that are CENTRALIZED in platform/app (see lists below).
#   4. Renames duplicate bean classes with a module prefix (GlobalExceptionHandler…).
#   5. Rewrites all JwtUtil imports to the shared platform JwtUtil.
#   6. Copies each original application.yaml to migration-reference/ for diffing.
# Then it builds the platform module's classes by copying the canonical
# implementations (auth's JwtUtil & WebClientConfig, sales' JacksonConfig &
# TimezoneValidationBean, auth's RedisConfig) with rewritten packages.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-../bikri-kendra}"
[[ -d "$SRC" ]] || { echo "❌ bikri-kendra not found at $SRC"; exit 1; }

# Guard: after the monolith gains hand-written changes (e.g. cross-module SQL joins in
# ReportServiceImpl), re-copying from bikri-kendra would ERASE them. Force explicitly.
if [[ -f .migrated && "${2:-}" != "--force" ]]; then
  echo "❌ Already migrated (this tree now contains hand-written monolith code)."
  echo "   Re-running would overwrite it. Use: ./scripts/migrate.sh $SRC --force"
  exit 1
fi
touch .migrated

# module → source service dir
declare -A SVC=( [auth]=auth-service [user]=user-service [inventory]=inventory-service
                 [sales]=sales-service [billing]=billing-service [printer]=printer-service )

# Classes replaced by ONE shared copy (deleted from every module):
CENTRALIZED="JacksonConfig RedisConfig WebClientConfig FlywayConfig R2dbcConfig \
TimezoneValidationBean JwtUtil ReactiveSecurityConfig SecurityConfig"

# Duplicate bean classes that stay per-module but need unique names:
RENAME_PER_MODULE="GlobalExceptionHandler UserServiceProperties"

cap() { echo "$1" | awk '{print toupper(substr($0,1,1)) substr($0,2)}'; }

for mod in auth user inventory sales billing printer; do
  svc="${SVC[$mod]}"
  echo "══════ $mod  ←  $svc ══════"

  rm -rf "$mod/src/main/java" "$mod/src/main/resources" 2>/dev/null || true
  mkdir -p "$mod/src/main/java"
  cp -R "$SRC/$svc/src/main/java/." "$mod/src/main/java/"

  # 2. migrations, namespaced per module (history tables handled by MultiModuleFlywayConfig)
  if [[ -d "$SRC/$svc/src/main/resources/db/migration" ]]; then
    mkdir -p "$mod/src/main/resources/db/migration/$mod"
    cp "$SRC/$svc/src/main/resources/db/migration/"*.sql "$mod/src/main/resources/db/migration/$mod/"
  fi

  # 6. reference copy of original config
  mkdir -p migration-reference
  cp "$SRC/$svc/src/main/resources/application.yaml" "migration-reference/$mod-application.yaml" 2>/dev/null || true

  # 3. delete boot classes + centralized classes
  find "$mod/src/main/java" -name "*Application.java" -delete
  for c in $CENTRALIZED; do
    find "$mod/src/main/java" -name "$c.java" -delete
  done

  # 4. rename per-module duplicates (class name + filename + in-module references)
  P="$(cap "$mod")"
  for c in $RENAME_PER_MODULE; do
    f="$(find "$mod/src/main/java" -name "$c.java" | head -1 || true)"
    if [[ -n "$f" ]]; then
      (grep -rl --include="*.java" "\b$c\b" "$mod/src/main/java" || true) | while read -r g; do
        perl -pi -e "s/\\b$c\\b/${P}$c/g" "$g"
      done
      mv "$f" "$(dirname "$f")/${P}$c.java"
      echo "  renamed $c → ${P}$c"
    fi
  done

  # 5. point every module at the shared JwtUtil
  (grep -rl --include="*.java" "\.JwtUtil;" "$mod/src/main/java" || true) | while read -r g; do
    perl -pi -e 's/import com\.vy\.[a-zA-Z0-9_.]+\.JwtUtil;/import com.vy.sales.platform.security.JwtUtil;/' "$g"
  done
done

# ── platform module: canonical shared classes with rewritten packages ────────
echo "══════ platform ══════"
PSEC=platform/src/main/java/com/vy/sales/platform/security
PCFG=platform/src/main/java/com/vy/sales/platform/config
rm -rf platform/src/main/java 2>/dev/null || true
mkdir -p "$PSEC" "$PCFG"

copy_pkg() { # src-file dst-dir new-package
  local src="$1" dst="$2" pkg="$3"
  cp "$src" "$dst/"
  perl -pi -e "s/^package .*;/package $pkg;/" "$dst/$(basename "$src")"
}
# auth's JwtUtil is the superset (generation + caffeine cache + eventId claims)
copy_pkg "$SRC/auth-service/src/main/java/com/vy/sales/auth/utils/JwtUtil.java"                    "$PSEC" com.vy.sales.platform.security
copy_pkg "$SRC/auth-service/src/main/java/com/vy/sales/auth/config/WebClientConfig.java"           "$PCFG" com.vy.sales.platform.config
copy_pkg "$SRC/auth-service/src/main/java/com/vy/sales/auth/config/RedisConfig.java"               "$PCFG" com.vy.sales.platform.config
copy_pkg "$SRC/sales-service/src/main/java/com/vy/sales/sales_service/config/JacksonConfig.java"   "$PCFG" com.vy.sales.platform.config
copy_pkg "$SRC/sales-service/src/main/java/com/vy/sales/sales_service/config/TimezoneValidationBean.java" "$PCFG" com.vy.sales.platform.config

# auth module code imports its own (now deleted) JwtUtil — already rewritten in the loop above.

# logback: single copy in app
cp "$SRC/auth-service/src/main/resources/logback-spring.xml" app/src/main/resources/ 2>/dev/null || true

echo ""
echo "✅ migration copy complete. Next:"
echo "   1. ./mvnw clean package -DskipTests     (fix any residual duplicate-bean names it reports)"
echo "   2. ./scripts/merge-databases.sh         (one-time DB consolidation)"
echo "   3. ./mvnw spring-boot:run -pl app"
