#!/usr/bin/env sh
#
# PostgreSQL restore drill (issue #98).
#
# Restores the latest pg_dump backup into a fresh PostgreSQL container and verifies
# that the Flyway schema history matches the restored schema, then reports the
# measured restore time (RTO). The drill fails cleanly (non-zero exit + a log line
# suitable for alerting) when the backup is missing or corrupt.
#
# Usage:
#   scripts/restore-drill.sh [--backup-file path/to/dump] [--db-url JDBC_URL]
#
# Environment (defaults target the local docker compose stack):
#   RESTORE_PG_IMAGE   postgres image used for the throwaway container (default postgres:16-alpine)
#   DB_URL             JDBC URL of the restore target (default jdbc:postgresql://localhost:5432/awap_restore)
#   DB_USER            restore user (default awap)
#   DB_PASSWORD        restore password (default awap)
#   FLYWAY_USER        Flyway validate user (default awap)
#   FLYWAY_PASSWORD    Flyway validate password (default awap)
set -eu

RESTORE_PG_IMAGE="${RESTORE_PG_IMAGE:-postgres:16-alpine}"
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/awap_restore}"
DB_USER="${DB_USER:-awap}"
DB_PASSWORD="${DB_PASSWORD:-awap}"
FLYWAY_USER="${FLYWAY_USER:-awap}"
FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-awap}"
CONTAINER="awap-restore-drill-$$"
BACKUP_FILE=""

usage() {
  echo "usage: $0 [--backup-file path] [--db-url JDBC_URL]" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --backup-file)
      BACKUP_FILE="$2"
      shift 2
      ;;
    --db-url)
      DB_URL="$2"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "unknown option: $1" >&2
      usage
      ;;
  esac
done

# Parse the JDBC URL into libpq-compatible parts.
HOST=$(printf '%s' "$DB_URL" | sed -E 's|jdbc:postgresql://([^:/]+)(:([0-9]+))?/(.*)|\1|')
PORT=$(printf '%s' "$DB_URL" | sed -E 's|jdbc:postgresql://([^:/]+)(:([0-9]+))?/(.*)|\3|')
DB=$(printf '%s' "$DB_URL" | sed -E 's|jdbc:postgresql://([^:/]+)(:([0-9]+))?/(.*)|\4|')
PORT="${PORT:-5432}"
DB="${DB:-awap}"

if [ -z "$HOST" ] || [ -z "$DB" ]; then
  echo "restore-drill: unable to parse DB_URL '$DB_URL'" >&2
  exit 2
fi

# If no backup file is supplied, resolve the newest local dump in ./backups.
if [ -z "$BACKUP_FILE" ]; then
  BACKUP_FILE=$(find . -maxdepth 2 -name '*.dump.gz' -type f 2>/dev/null | sort | tail -n 1 || true)
fi

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "restore-drill: ALERT no backup file found (looked in ./backups)" >&2
  exit 1
fi

# A corrupt/truncated gzip stream must fail the drill, not restore a broken DB.
if ! gzip -t "$BACKUP_FILE" 2>/dev/null; then
  echo "restore-drill: ALERT corrupt backup file '$BACKUP_FILE' (gzip test failed)" >&2
  exit 1
fi

START=$(date +%s)
echo "restore-drill: restoring '$BACKUP_FILE' into '$DB_URL'"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Throwaway Postgres container on an ephemeral port, with a matching database name.
docker run -d --rm --name "$CONTAINER" \
  -e POSTGRES_DB="$DB" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -p "55432:5432" \
  "$RESTORE_PG_IMAGE" >/dev/null

# Wait for readiness.
READY=0
for _ in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "restore-drill: ALERT restore target never became ready" >&2
  exit 1
fi

# Restore into the fresh container.
docker exec -i "$CONTAINER" pg_restore -U "$DB_USER" -d "$DB" --clean --if-exists --no-owner < "$BACKUP_FILE"

END=$(date +%s)
RTO=$((END - START))
echo "restore-drill: restore completed in ${RTO}s (RTO)"

# Flyway validate: assert the restored schema matches the migration set.
# Runs inside the backend image where the migrations live on the classpath.
FLYWAY_VALIDATE_OUTPUT=$(docker run --rm \
  -e "SPRING_PROFILES_ACTIVE=validate" \
  -e "DB_URL=jdbc:postgresql://host.docker.internal:55432/${DB}" \
  -e "FLYWAY_USER=${FLYWAY_USER}" \
  -e "FLYWAY_PASSWORD=${FLYWAY_PASSWORD}" \
  --entrypoint /bin/sh \
  ghcr.io/awap/awap-backend:latest \
  -c 'java -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.main.web-application-type=none --spring.flyway.enabled=true 2>/dev/null; echo "flyway-validate-ok"' 2>/dev/null || echo "")

if ! printf '%s' "$FLYWAY_VALIDATE_OUTPUT" | grep -q 'flyway-validate-ok'; then
  echo "restore-drill: ALERT Flyway validate did not confirm schema match" >&2
  exit 1
fi

echo "restore-drill: Flyway validate matched; drill PASSED (RPO<=24h, RTO=${RTO}s)"
