#!/usr/bin/env bash
# =============================================================================
# merge-databases.sh — one-time consolidation of the six per-service databases
# into ONE database (default: bikri_db). Compatible with macOS bash 3.2.
#
#   ./scripts/merge-databases.sh [-h host] [-P port] [-u user] [--target bikri_db] [--dry-run]
#
# Safe by construction:
#   1. Full mysqldump backup of every source database first.
#   2. Aborts if any table name exists in two source databases.
#   3. RENAME TABLE — atomic, instant (metadata only), reversible from the log.
#   4. flyway_schema_history → flyway_schema_history_<module> (keeps versions+checksums).
#   5. Old (now empty) databases are left in place; drop them manually later.
# =============================================================================
set -euo pipefail

DB_HOST_ARG=127.0.0.1 DB_PORT_ARG=3306 DB_USER_ARG=root TARGET=bikri_db DRY_RUN=false
while [ $# -gt 0 ]; do
  case "$1" in
    -h) DB_HOST_ARG="$2"; shift 2 ;;
    -P) DB_PORT_ARG="$2"; shift 2 ;;
    -u) DB_USER_ARG="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

printf "MySQL password for %s@%s: " "$DB_USER_ARG" "$DB_HOST_ARG"
stty -echo; read -r PW; stty echo; printf "\n"
M() { mysql -h"$DB_HOST_ARG" -P"$DB_PORT_ARG" -u"$DB_USER_ARG" -p"$PW" -N -B -e "$1"; }

# module:database pairs (no associative arrays — macOS bash 3.2)
PAIRS="user:user_db inventory:inventory_db sales:sales_db billing:billing_db printer:printer_db auth:auth_db"

# Which sources actually exist?
EXISTING=""
for pair in $PAIRS; do
  db="${pair#*:}"
  if M "SHOW DATABASES LIKE '$db'" | grep -q "$db"; then EXISTING="$EXISTING $pair"; fi
done
[ -n "$EXISTING" ] || { echo "❌ none of the source databases exist on this server"; exit 1; }
echo "▶ Source databases found:$EXISTING"

# 1. Backup
STAMP="$(date +%F-%H%M)"
BACKUP="backup-before-merge-$STAMP.sql.gz"
if [ "$DRY_RUN" = "false" ]; then
  DBS=""
  for pair in $EXISTING; do DBS="$DBS ${pair#*:}"; done
  echo "▶ Backing up:$DBS → $BACKUP"
  # shellcheck disable=SC2086
  mysqldump -h"$DB_HOST_ARG" -P"$DB_PORT_ARG" -u"$DB_USER_ARG" -p"$PW" \
    --databases $DBS --single-transaction | gzip > "$BACKUP"
fi

# 2. Collision check
IN_LIST=""
for pair in $EXISTING; do IN_LIST="$IN_LIST,'${pair#*:}'"; done
IN_LIST="${IN_LIST#,}"
echo "▶ Checking for table-name collisions…"
COLLIDING=$(M "SELECT table_name FROM information_schema.tables
              WHERE table_schema IN ($IN_LIST) AND table_name <> 'flyway_schema_history'
              GROUP BY table_name HAVING COUNT(*) > 1;")
if [ -n "$COLLIDING" ]; then
  echo "❌ Duplicate table names across source DBs — resolve first:"; echo "$COLLIDING"; exit 1
fi
echo "  ✅ no collisions"

# 3+4. Renames
[ "$DRY_RUN" = "true" ] || M "CREATE DATABASE IF NOT EXISTS \`$TARGET\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

for pair in $EXISTING; do
  mod="${pair%%:*}"; db="${pair#*:}"
  TABLES=$(M "SELECT table_name FROM information_schema.tables
              WHERE table_schema='$db' AND table_type='BASE TABLE';")
  for t in $TABLES; do
    if [ "$t" = "flyway_schema_history" ]; then
      SQL="RENAME TABLE \`$db\`.\`$t\` TO \`$TARGET\`.\`flyway_schema_history_${mod}\`;"
    else
      SQL="RENAME TABLE \`$db\`.\`$t\` TO \`$TARGET\`.\`$t\`;"
    fi
    echo "  $SQL"
    [ "$DRY_RUN" = "true" ] || M "$SQL"
  done
done

echo ""
if [ "$DRY_RUN" = "true" ]; then
  echo "✅ DRY RUN complete — nothing changed."
else
  echo "✅ Merge complete into '$TARGET'. Backup: $BACKUP"
  echo "   Drop the empty old databases once the monolith is proven:"
  for pair in $EXISTING; do echo "     DROP DATABASE ${pair#*:};"; done
fi
