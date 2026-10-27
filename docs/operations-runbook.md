# Operations Runbook (İP-4)

## O1 — Dev/Prod config drift prevention

- Runtime configuration is driven by environment variables (`DB_URL`, `CORS_ALLOWED_ORIGINS`,
  `API_ORIGINS`, `OUTBOX_RETENTION_DAYS`, `WEBHOOK_*`, ...) in
  [`application.yml`](../backend/identity/src/main/resources/application.yml),
  [`application-prod.yml`](../backend/identity/src/main/resources/application-prod.yml) and
  [`docker-compose.yml`](../infra/docker-compose.yml).
- Secrets must be injected via Vault (prod) or env (local, see
  [`infra/.env.example`](../infra/.env.example)); never committed.
- Drift check: keep the Kubernetes manifests in
  [`infra/k8s`](../infra/k8s) and the Compose env list in sync with the Spring property names.

## O2 — End-to-end tracing

Micrometer Tracing + OTLP are enabled in
[`application.yml`](../backend/identity/src/main/resources/application.yml) (`management.tracing`,
`management.otlp.tracing.endpoint`). The trace context is propagated through the HTTP filters and
the Kafka producer/consumer headers so a single `traceId` spans the request and the outbox relay.

Verification (one request = one traceId across all spans):

1. Start the stack and send one request through the HTTP API.
2. Query the OTel collector: `curl -s 'http://localhost:4318/v1/traces' | grep -c 'traceId'`.
3. Confirm every span for that request carries the same `traceId` (HTTP → service → outbox →
   Kafka consumer). A span missing the trace context indicates the Kafka header propagation is
   not wired and must be restored.

> The outbox relay reuses the same `KafkaTemplate` bean that Micrometer instruments, so the
> trace context is carried on the Kafka record headers automatically. No explicit header copy is
> required as long as `spring-kafka` observation is enabled.

## O3 — SLO alerts

Defined in [`infra/prometheus/alert-rules.yml`](../infra/prometheus/alert-rules.yml):
error rate > 5%, p95 latency > 2s, DLQ backlog (warning/critical) and instance-down.

## O4 — Backup & disaster recovery

- PostgreSQL: nightly logical dumps (`pg_dump`) via
  [`pg-backup-cronjob.yaml`](../infra/k8s/pg-backup-cronjob.yaml) + WAL archiving enabled in
  [`docker-compose.yml`](../infra/docker-compose.yml) (`archive_mode=on`). RPO ≤ 24h, RTO measured.
- MinIO: object versioning is enabled by `FileService`; the
  [`minio-replication-cronjob.yaml`](../infra/k8s/minio-replication-cronjob.yaml) mirrors
  document/file/backup buckets to a DR endpoint.
- Restore drill: [`restore-drill.sh`](../scripts/restore-drill.sh) restores the latest dump into a
  fresh container, verifies Flyway (`flyway_schema_history`) matches, and prints the measured RTO.
  A corrupt/missing backup fails the drill with an `ALERT` log line and a non-zero exit.

### Restore drill procedure

```sh
# Point the drill at a dump and a throwaway restore database.
scripts/restore-drill.sh --backup-file backups/awap-20260101T010000Z.dump.gz \
  --db-url jdbc:postgresql://localhost:5432/awap_restore
```

Record the printed RTO (seconds) after each drill and keep the value in the changelog or an
incident ticket. The target is RPO ≤ 24h / RTO ≤ 4h.

## O5 — Secrets management

- [`secrets.yaml`](../infra/k8s/secrets.yaml) contains only SealedSecrets; plain text lives in Vault.
- [`check-secrets.sh`](../scripts/check-secrets.sh) gates the repo: no plain-text `stringData`,
  no committed password/token literals, and a gitleaks full-history scan when available.
- Secret store unreachable policy: **prod fails fast** (Vault enabled and required), **dev degrades**
  (falls back to local env vars). This is configured via `VAULT_ENABLED` / `spring.cloud.vault`.

## O6 — Compliance

The control-to-policy mapping (SOC 2 / ISO 27001 / GDPR-KVKK) lives in
[`compliance-inventory.md`](../docs/compliance-inventory.md): retention windows, right-to-erasure
behaviour and the outbox/execution-log retention mapping.

## O7 — Analytics OpenSearch sink

**PostgreSQL remains authoritative.** Day-level aggregates in `analytics_aggregates` are written in
the same transaction as the business operation and power the dashboard and tenant reports. The
OpenSearch sink mirrors sanitized raw events for ad-hoc analysis; losing an event there is an
analytics fidelity issue, never a data-integrity issue. This is why an OpenSearch outage must not
block workflow execution and the health indicator reports `DEGRADED` rather than `DOWN`.

### Provisioning

Startup provisioning is idempotent (PUT, 409 treated as already provisioned) and uses the files in
`backend/identity/src/main/resources/opensearch/`:

- `awap-analytics-ism-policy.json` — ISM policy `awap-analytics-ism`: hot → warm at
  `OPENSEARCH_ANALYTICS_ILM_HOT_DAYS` (7d), delete at `OPENSEARCH_ANALYTICS_ILM_DELETE_DAYS`
  (180d). `OPENSEARCH_ANALYTICS_ILM_WARM_DAYS` (30d) documents the warm-phase budget.
- `awap-analytics-index-template.json` — `awap-analytics-template`, `dynamic: strict`, keyword
  mappings for `tenantId`/`eventType`/`workflowInstanceId`/`taskId`, `long` for `durationMs`, `date`
  for `timestamp`, and the ISM policy id.
- `awap-analytics-dlq-template.json` — dead-letter template (`awap-analytics-dlq-*`), higher
  priority than the main template so dead-letter documents are not rejected by strict mappings.

Deploying with an older OpenSearch that has not yet picked up the policy is harmless: ISM attaches
new indices through the template/`ism_template` and reconciles within its coordinator interval
(5s in the bundled 2.19 image, 10m sweep by default for existing indices).

### Sink behaviour

- Writes are buffered in a bounded queue (`OPENSEARCH_ANALYTICS_QUEUE_CAPACITY`, 1000) and sent as
  one `_bulk` request when `OPENSEARCH_ANALYTICS_BATCH_SIZE` (100) is reached, or as a deterministic
  single-document upsert for low volume. Deterministic `_id`s make retries idempotent.
- Retryable failures (429/5xx/timeouts) use `OPENSEARCH_ANALYTICS_RETRY_DELAYS_MS` (1s/5s/25s) up to
  `OPENSEARCH_ANALYTICS_MAX_ATTEMPTS` (4). Exhausted retries increment the failure/drop metrics.
- The circuit breaker opens after `OPENSEARCH_ANALYTICS_BREAKER_THRESHOLD` (5) consecutive failures,
  sheds writes for `OPENSEARCH_ANALYTICS_BREAKER_OPEN_MS` (30s), then half-opens with one probe.
- Mapping conflicts and malformed documents go once to `awap-analytics-dlq-YYYY.MM.dd`; they are
  never retried. Mapping conflicts normally mean a schema change was deployed without a template
  update.
- The PII allowlist reduces every document to `tenantId`, `eventType`, `workflowInstanceId`,
  `taskId`, `durationMs` and `timestamp`; nested objects are dropped and PII-looking values are
  masked before either the main or dead-letter write.

### Metrics, health and alerts

Micrometer exports `awap.analytics.sink.events{outcome=success|failure}`,
`awap.analytics.sink.dropped{reason=queue_overflow|circuit_open|retries_exhausted}`,
`awap.analytics.sink.dead_letter{reason=mapping_conflict|malformed|rejected}`,
`awap.analytics.sink.retries{reason=http_429|http_503|timeout|io_error}`,
`awap.analytics.sink.queue.depth`, `awap.analytics.sink.circuit.open` and
`awap.analytics.sink.provisioning{component,outcome}`.

Health endpoint: `GET /actuator/health` includes `openSearchAnalyticsSink` with `circuitState` and
`queueDepth`; it is `DEGRADED` while the circuit is open. Alerts live in
[`alert-rules.yml`](../infra/prometheus/alert-rules.yml) under `awap-analytics-sink`
(drops, dead letters, queue depth > 500, circuit open).

### Rollback

Set `OPENSEARCH_ENABLED=false` and restart; the no-op sink takes over and PostgreSQL aggregates keep
serving. Deleting the templates/policy is optional and only affects future indices. Raw event data
follows ISM deletion at 180 days; a tenant deletion does not remove its historical analytics events
early — retention is time-based and documented in the compliance inventory.
