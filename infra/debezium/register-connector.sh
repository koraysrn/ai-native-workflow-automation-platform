#!/usr/bin/env bash
# Registers (or updates) the outbox CDC connector against the local Connect worker (issue #131).
# Secrets are read from the environment; nothing is hard-coded.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONNECTOR_FILE="${CONNECTOR_FILE:-infra/debezium/outbox-connector.json}"
CONNECTOR_NAME="awap-outbox-connector"

export DB_HOST="${DB_HOST:-postgres}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-awap}"
export DB_PASSWORD="${DB_PASSWORD:-awap}"
export DB_NAME="${DB_NAME:-awap}"

for variable in DB_HOST DB_PORT DB_USER DB_PASSWORD DB_NAME; do
  if [[ -z "${!variable// /}" ]]; then
    echo "Missing required environment variable ${variable}" >&2
    exit 1
  fi
done

# Substitute only the DB_* placeholders. Debezium's own `${routedByValue}` placeholder must survive;
# a bare `envsubst` would replace it with an empty string and route every event to `outbox.event.`.
# The single-quoted list is intentional: envsubst receives the literal placeholders to substitute.
# shellcheck disable=SC2016
payload="$(envsubst '${DB_HOST} ${DB_PORT} ${DB_USER} ${DB_PASSWORD} ${DB_NAME}' < "${CONNECTOR_FILE}")"

if curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}" >/dev/null 2>&1; then
  echo "Updating connector ${CONNECTOR_NAME}"
  # PUT /connectors/{name}/config expects the config object, not the create envelope.
  config_payload="$(
    node -e 'const fs=require("fs");const p=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(JSON.stringify(p.config ?? p));' \
      <<<"${payload}"
  )"
  curl -fsS -X PUT -H "Content-Type: application/json" \
    --data "${config_payload}" "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config"
else
  echo "Creating connector ${CONNECTOR_NAME}"
  curl -fsS -X POST -H "Content-Type: application/json" \
    --data "${payload}" "${CONNECT_URL}/connectors"
fi

echo
echo "Connector status:"
# The worker returns 404 for a short window after the create call; poll instead of failing the job.
for _ in $(seq 1 30); do
  if status="$(curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status")"; then
    printf '%s\n' "${status}"
    exit 0
  fi
  sleep 2
done

echo "Connector status endpoint did not become ready within 60s" >&2
exit 1
