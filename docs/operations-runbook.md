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

## O7 — Notification de-duplication (TTL + business key)

Every delivery row carries `dedupe_expires_at`. The `(tenant_id, dedupe_key)` unique constraint only
rejects a duplicate while that window is active; a terminal row whose window closed is renamed to an
archival key by the `awap_notification_dedupe_guard` trigger, and a new delivery may be written.

Key policy (`DedupePolicy`):

| Policy | Event types | Key material |
|---|---|---|
| `TRANSACTIONAL` | `WORKFLOW_*`, `TASK_*`, `APPROVAL_*`, `FORM_*`, `NOTIFICATION_REQUESTED`, unknown | tenant + channel + recipient + template + business key (or payload hash when absent) |
| `PERIODIC` | `TASK_ESCALATED`, `TASK_REMINDER`, `SLA_BREACH`, `SLA_WARNING`, `DAILY_DIGEST`, `WEEKLY_DIGEST` | tenant + channel + recipient + template + fixed periodic sentinel |

The key is always a 64-character SHA-256 hex digest, so no producer can bloat the unique index. An
explicit `dedupeKey` is hashed together with the tenant; a `businessKey` (workflow instance, task,
approval or form id) supplied by the producer wins over the payload fallback.

Configuration:

- `awap.notification.dedupe-window-seconds` (env `NOTIFICATION_DEDUPE_WINDOW_SECONDS`, default `3600`).
- `awap.notification.dedupe-template-windows.<template>` optional per-template override. Non-positive
  values and unknown template keys fail application startup (misconfiguration is not silent).

Operations:

- `awap_notification_dedupe_suppressed_total{policy,template,reason}` (`awap.notification.dedupe.suppressed`)
  counts suppressed repeats; a sudden drop combined with a send spike indicates the window is too
  short or `dedupe_expires_at` is not being set.
- Purge (`awap_purge_notification_deliveries`) only removes terminal rows whose audit window **and**
  dedupe window have closed, so retention can never make an active key reusable.
- Rollback: the migration is additive (new nullable-then-backfilled column, new trigger and a
  `CREATE OR REPLACE` purge function). Rolling the application back keeps the columns; dedupe falls
  back to the old 30-day behaviour only if the trigger is dropped, so do not drop it during a
  rolling deploy.
