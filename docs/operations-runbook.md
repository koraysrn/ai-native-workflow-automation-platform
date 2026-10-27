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

## O7 — Notification retries (issue #119)

The Kafka consumer performs only the first provider attempt. It persists the delivery row (plus
`nextAttemptAt`) and returns; no retry ever sleeps inside the consumer transaction. The offset is
committed only after the listener returns, so a database outage re-delivers the record instead of
losing it.

| Setting | Default | Meaning |
|---|---|---|
| `awap.notification.retry-interval-ms` | 5000 | scheduler tick |
| `awap.notification.retry-delays-ms` | 1000,5000,25000,120000,600000 | backoff per failed attempt |
| `awap.notification.max-attempts` | 5 | attempts before `FAILED` + dead letter |
| `awap.notification.retry-lease-seconds` | 120 | claim window before another replica may take over |
| `awap.notification.retry-batch-size` | 50 | rows per tick |
| `awap.notification.retry-per-tenant-limit` | 5 | fairness cap per tenant per tick |
| `awap.notification.max-body-bytes` | 262144 | rendered body limit (permanent failure above it) |
| `awap.notification.first-attempt-grace-ms` | 60000 | scheduler waits this long before retrying an in-flight first attempt |
| `awap.notification.retention-days` | 30 | SENT/FAILED audit window |
| `awap.notification.retry-retention-days` | 7 | PENDING/RETRYING purge window |

Operational behaviour:

- **Claiming** uses `awap_claim_notification_retries` (SECURITY DEFINER, pinned `search_path`) with
  `FOR UPDATE SKIP LOCKED` and a per-tenant `row_number()` cap. Concurrent replicas therefore never
  process the same row while a lease is valid.
- **Crash recovery**: if a replica dies after claiming, the lease expires and another replica claims
  the row. A replica that lost its lease cannot write SENT/RETRYING/FAILED because every update is
  guarded by `lease_token` and the retriable status.
- **Exhaustion**: at `max-attempts` the row becomes `FAILED`, `NOTIFICATION_FAILED` and
  `NOTIFICATION_DLQ` are written to the outbox and the relay publishes to `notification.send.dlq`.
  The `awap-dlq` consumer counts it and the alerts below fire.
- **Permanent failures** (unrenderable template, oversized body, missing adapter) are `FAILED`
  immediately with no retry, and are dead-lettered for inspection.
- **Metrics**: `awap.notification.retry.lag` (timer), `awap.notification.retry.claims`,
  `awap.notification.retry.attempts`, `awap.notification.retry.failures`,
  `awap.notification.retry.successes`, `awap.notification.retry.lease.conflicts`,
  `awap.notification.retry.permanent.failures`, `awap.notification.retry.dlq`,
  `awap.notification.retention.purged`.
- **Alerts** (`infra/prometheus/alert-rules.yml`, group `awap-notification-retry`): retry lag p95 >
  60s, any dead-lettered delivery, and elevated lease conflicts.

Manual recovery: inspect `notification.send.dlq` for the poison payload, then either fix the
template/provider and reset the row (`UPDATE notification_deliveries SET status='RETRYING',
next_attempt_at=now(), lease_until=NULL, lease_token=NULL WHERE id=...`) or leave it `FAILED`.
Rollback: the scheduler is additive; set `NOTIFICATION_RETRY_SCHEDULER_ENABLED=false` to stop the
retry loop, and revert V42 only after the migration has been released (destructive rollback drops
the retry columns).
