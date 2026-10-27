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

## O8 — Tenant usage metering and quota

**Model.** `usage_counters` holds one row per `(tenant_id, day, metric)`; `day` is always the UTC
calendar day. `usage_events` is the idempotency ledger whose primary key is the per-event key, so
an HTTP retry or a redelivered Kafka record cannot double count. Metric keys are a bounded enum
(`workflow_executions`, `task_decisions`, `ai_calls`, `document_bytes`, `active_users`,
`api_requests`); never add a free-form key because the value is used as a Prometheus label.
`tenant_usage_limits` carries per-tenant daily overrides; missing rows fall back to the documented
enum defaults (workflow 1000/day, task decisions 10000/day, AI calls 1000/day, document bytes 5 GiB,
active users 100, API requests 1 000 000).

**Write path.** `awap_usage_record_event` inserts the event key and conditionally updates the
counter in one statement. The `UPDATE … WHERE value + delta <= limit` predicate is re-evaluated
after the row-lock wait, so N concurrent attempts at the boundary admit exactly `limit` increments.
Rejected attempts roll their ledger row back. Synchronous hooks (workflow start, task decision,
document version, tenant-aware AI call) and the `analytics.events` consumer share the same
deterministic key (`usage:workflow-started:<instanceId>`, `usage:task-decision:<taskId>`, …).

**Failure policy.** Observational metering (task decisions, document bytes) is **fail-open**: a
metering outage increments `awap.usage.metering.failures` and the business operation proceeds.
Quota-enforcing metering (workflow start, AI call) is **fail-closed** by default and returns
`503 AWAP-IDN-0008`; set `USAGE_METERING_FAIL_OPEN=true` to prefer availability over strict quota.
A genuine limit breach always returns `429 AWAP-IDN-0006` with the metric and limit in `detail`.

**API.** `GET /api/v1/tenants/{id}/usage?from=&to=&metric=` returns per-day values, the effective
limit and the remaining quota. Omitting the window defaults to the last 30 UTC days. Unknown metric
keys return `400 AWAP-IDN-0007`; a soft-deleted or unknown tenant returns `404`.

**Reconciliation and retention.** `awap.usage.reconciliation-cron` (default `0 20 2 * * *`) recomputes
the last three UTC days from `usage_events`, repairs drift and exports
`awap.usage.reconciliation.drift{metric}` / `awap.usage.reconciliation.runs{outcome}`.
`awap.usage.retention-cron` (default `0 40 3 * * *`) moves counters older than
`USAGE_RETENTION_DAYS` (400) and all counters of soft-deleted tenants to `usage_counter_archive`,
then deletes the live rows and their raw events. Archive rows are kept for audit and reporting.

**Metrics and alerts.** Micrometer exports `awap.usage.increments{metric}`,
`awap.usage.quota.near_limit{metric}` (≥80% of the daily limit), `awap.usage.quota.exceeded{metric}`
(rejected request), `awap.usage.metering.failures{metric}`, `awap.usage.archived` and the
reconciliation series above. Prometheus alerts live in `alert-rules.yml` under `awap-usage`.

**Rollback.** The feature is additive and the read API is independent of the write hooks. To disable
enforcement without losing telemetry, set `USAGE_METERING_FAIL_OPEN=true`; to stop metering
entirely, remove the hooks in a follow-up release and keep the tables (they are read-only then).
V45 does not alter existing tables, so no data migration or down-migration is required.
