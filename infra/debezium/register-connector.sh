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

# Substitute ${VAR} placeholders without eval so the JSON stays under our control.
payload="$(envsubst < "${CONNECTOR_FILE}")"

if curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}" >/dev/null 2>&1; then
  echo "Updating connector ${CONNECTOR_NAME}"
  curl -fsS -X PUT -H "Content-Type: application/json" \
    --data "${payload}" "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config"
else
  echo "Creating connector ${CONNECTOR_NAME}"
  curl -fsS -X POST -H "Content-Type: application/json" \
    --data "${payload}" "${CONNECT_URL}/connectors"
fi

echo
echo "Connector status:"
curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status"
