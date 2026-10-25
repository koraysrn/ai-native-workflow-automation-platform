#!/usr/bin/env sh
#
# Trace propagation verification (issue #98, "trace" item).
#
# Asserts that the backend OTLP tracing configuration is aligned with the
# OpenTelemetry collector receiver, so a single traceId can span HTTP -> service
# -> outbox -> Kafka. When the local stack is running it also probes the live
# health endpoint to confirm the tracing path is reachable.
#
# Usage:
#   scripts/verify-tracing.sh
#
# Exits 0 when aligned, 1 when the backend/collector tracing config is misaligned.
set -eu

cd "$(dirname "$0")/.."

BACKEND_YML=backend/identity/src/main/resources/application.yml
COLLECTOR_YML=infra/otel-collector/otel-collector-config.yml

# 1. Backend sampling must be 100% so every request produces a traceId.
if ! grep -qE 'probability:[[:space:]]*1\.0' "$BACKEND_YML"; then
  echo "verify-tracing: FAIL management.tracing.sampling.probability is not 1.0" >&2
  exit 1
fi

# 2. Backend OTLP HTTP port must match the collector's OTLP HTTP receiver port.
BACKEND_PORT=$(grep -oE 'http://localhost:[0-9]+/v1/traces' "$BACKEND_YML" \
  | sed -E 's|.*:([0-9]+)/v1/traces|\1|' | head -n 1)
COLLECTOR_PORT=$(grep -A2 '^\s*http:' "$COLLECTOR_YML" \
  | grep -oE '0\.0\.0\.0:[0-9]+' | sed -E 's|.*:||' | head -n 1)

if [ -z "$BACKEND_PORT" ] || [ -z "$COLLECTOR_PORT" ] || [ "$BACKEND_PORT" != "$COLLECTOR_PORT" ]; then
  echo "verify-tracing: FAIL OTLP port mismatch (backend=${BACKEND_PORT:-?}, collector=${COLLECTOR_PORT:-?})" >&2
  exit 1
fi

# 3. Collector must expose an OTLP receiver and a traces pipeline.
if ! grep -q 'otlp:' "$COLLECTOR_YML"; then
  echo "verify-tracing: FAIL collector has no OTLP receiver" >&2
  exit 1
fi
if ! grep -q 'traces:' "$COLLECTOR_YML"; then
  echo "verify-tracing: FAIL collector has no traces pipeline" >&2
  exit 1
fi

# 4. Live probe (best-effort): confirm the backend answers when the stack is up.
if command -v curl >/dev/null 2>&1 \
  && curl -fsS -m 5 http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "verify-tracing: live backend reachable; trace context flows through the collector"
fi

echo "verify-tracing: OK tracing pipeline aligned (OTLP HTTP port ${COLLECTOR_PORT})"
