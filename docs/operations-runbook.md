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

## Notification templates, cache and rendering metrics

Tenant templates live in `notification_templates` (unique per `(tenant_id, template_key, locale)`)
with every saved revision copied to `notification_template_versions`. The `version` column is the
optimistic locking token: a stale `PUT` returns `409`, and concurrent edits never overwrite each
other silently. Built-in EN/TR templates are seeded by `V47__notification_template.sql` for tenants
that exist when the migration runs; new tenants rely on the code fallback in
`NotificationTemplateRenderer`, so a missing row never blocks a delivery.

Resolution order for a delivery: tenant template for the requested locale → tenant template for
`en` → built-in code default → the raw template key. Archived templates are excluded, which makes
archive a safe rollback to the built-in default.

| Metric | Type | Tags | Use |
|---|---|---|---|
| `awap.notification.template.cache` | counter | `result` (`hit`/`miss`) | Template cache efficiency |
| `awap.notification.template.render.failures` | counter | – | Placeholder/variable render failures (should stay at 0) |

```promql
# Cache hit ratio (target > 0.9 for steady traffic).
sum(rate(awap_notification_template_cache_total{result="hit"}[5m]))
  / sum(rate(awap_notification_template_cache_total[5m]))
# Any render failure is a template bug; alert on a non-zero rate.
sum(rate(awap_notification_template_render_failures_total[5m])) > 0
```

Template rules enforced by `NotificationTemplateValidator`: placeholders must look like
`{{name}}` (letters, digits, `.`, `_`, `-`), every `{{` needs a `}}`, the body is capped at 1 MiB and
every substituted value is HTML-escaped (`&`, `<`, `>`, `"`, `'`). Preview rejects placeholders the
supplied variables do not cover with `400`.

### Rollback and data migration

V47 is additive (two tables, two indexes). Rolling the application back is safe: the previous
release ignores the new tables and keeps using the code defaults. Rolling the schema back drops the
audit history, so restore from a pre-V47 backup when revisions must be preserved. Editing is guarded
by `INSTANCE_MANAGE`; reads require `INSTANCE_READ`.
