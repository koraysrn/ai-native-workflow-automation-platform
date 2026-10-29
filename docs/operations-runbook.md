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

Defined in [`infra/prometheus/alert-rules.yml`](../infra/prometheus/alert-rules.yml). Every alert
carries `severity`, `summary`, `description` and a `runbook_url` pointing back to this document.

| Alert | Condition | Severity | First action |
|---|---|---|---|
| `AwapDlqMessages` / `AwapDlqMessagesCritical` | DLQ growth > 5 / > 50 in 5m | warning / critical | Inspect the consumer pipeline, replay or park the poison messages |
| `AwapHighErrorRate` | 5xx ratio > 5% for 2m | warning | Check recent deploys and the failing endpoint in the dashboard |
| `AwapHighLatency` | HTTP p95 > 2s for 5m | warning | Compare with the process-duration panel and DB pool saturation |
| `AwapInstanceDown` | scrape target unreachable for 1m | critical | Check pod events, then the readiness probe |
| `AwapWorkflowFailureRateHigh` | workflow failure ratio > 20% for 10m | warning | Open the workflow failure panel and the failed instance logs |
| `AwapNotificationRetryLagHigh` | notification retry-lag p95 > 10s for 10m | warning | Check provider status and the delivery attempts in the inbox |
| `AwapWebhookDeadGrowth` | > 10 DLQ messages in 15m | critical | Verify the webhook target allowlist/credentials; inspect dead letters |
| `AwapOutboxBacklogStuck` | `awap_outbox_pending` > 1000 for 15m | critical | Check Kafka reachability and the relay logs; outbox writes are not draining |

Dashboard: `AWAP platform overview` (`infra/grafana/provisioning/dashboards/awap.json`) with
`tenant` and `channel` template variables. Panel/metric mapping:
[`docs/observability-metrics.md`](../docs/observability-metrics.md).

Dashboard and alert definitions are validated in CI: the JSON is parsed and every referenced metric
must exist in [`infra/grafana/metric-catalog.json`](../infra/grafana/metric-catalog.json), and
`promtool check rules` validates the alert file.

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

## O7 — Webhook delivery queue and dead-letter handling

WEBHOOK workflow nodes make the first HTTP attempt synchronously. Retryable failures (transport
errors, 5xx, 408, 429) are persisted in `webhook_deliveries` and retried by `WebhookRetryScheduler`
(5s fixed delay, `FOR UPDATE SKIP LOCKED` claim with a lease token). Permanent failures (any other
4xx) and exhausted budgets end in `status = DEAD` and fail the node and its instance.

Retry schedule: 1s, 5s, 25s, 2m, 10m with up to 20% jitter; `429` honours `Retry-After`. The attempt
budget comes from the node config (`maxAttempts`, default 3, capped at 10) and is stored on the row
as `max_attempts`.

### Metrics and alerts

| Metric | Meaning | Action |
|---|---|---|
| `awap.webhook.attempts{result,status}` | One increment per HTTP attempt (`sent`/`retry`/`dead`) | `dead` rising means a target is down |
| `awap.webhook.dead` | Deliveries that exhausted their budget / hit a permanent error | Page on-call when it increases |
| `awap.webhook.retry_lag_seconds` | Scheduled instant vs. actual claim instant | p95 > 5s means the queue is backlogged: raise `WEBHOOK_RETRY_BATCH_SIZE` / add replicas |

A `WEBHOOK_DELIVERY_DEAD` outbox event is written to the `webhook.delivery.dlq` topic (it matches the
`.*\.dlq` pattern of `DlqConsumer`, which increments `awap.dlq.messages`). Alert on
`rate(awap.webhook.dead[5m]) > 0` and on a `awap.dlq.messages` increase.

### Inspecting DEAD rows

```sh
# Tenant-scoped API (requires INSTANCE_READ; tenant comes from the session)
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.example.com/api/v1/webhook-deliveries?status=DEAD&limit=50"
```

```sql
-- Direct inspection as a DBA role (RLS applies to awap_app, so set the tenant first)
SET awap.tenant_id = '<tenant-uuid>';
SELECT id, workflow_instance_id, node_id, attempts, max_attempts, last_status_code,
       left(last_error, 200) AS error, created_at, expires_at
FROM webhook_deliveries
WHERE status = 'DEAD'
ORDER BY created_at DESC;
```

Never select `payload_json` or log it: it may contain personal data. `target_url` may contain a
query string that is already redacted in the API but not in the table.

### Re-queue procedure

1. Identify the root cause (target downtime, wrong allowlist, secret rotation). `403` rows usually
   mean the SSRF guard/allowlist rejected the host, so fix config first.
2. Re-queue the row (keeps the original `idempotency_key`, so the receiver de-duplicates):

```sql
SET awap.tenant_id = '<tenant-uuid>';
UPDATE webhook_deliveries
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), lease_token = NULL,
    lease_until = NULL, updated_at = now()
WHERE id = '<delivery-id>' AND status = 'DEAD';
```

3. The scheduler picks the row up within 5s and performs exactly one attempt per claim. A successful
   retry completes the node via the outbox `NodeCompletedEvent`; another failure returns the row to
   `DEAD` and fails the instance again.
4. If the target is permanently gone, cancel instead of re-queueing (`status = 'CANCELLED'`) and
   cancel the workflow instance through the API.

### Other operational notes

- Cancelling a workflow instance marks its open deliveries `CANCELLED`; no further attempt is made.
- `expires_at` (default 7 days, `WEBHOOK_RETRY_RETENTION_DAYS`) bounds the queue: expired rows are
  cancelled by the scheduler sweep and can be purged with
  `DELETE FROM webhook_deliveries WHERE expires_at < now() - interval '1 day'`.
- URL, payload, idempotency key and HMAC signing are immutable across retries; SSRF guard and
  allowlist run on every attempt. Never disable them to "unstick" a delivery.
## O8 — Notification read state, retention and metrics

In-app notifications are acknowledged through `notification_deliveries.read_at` (single-user per
delivery row, see `V46__notification_read_state.sql`). The API surface is:

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/api/v1/notifications?unreadOnly=true` | Inbox listing, newest first, optional unread filter |
| `GET` | `/api/v1/notifications/unread-count` | Unread badge count (matches the unread listing) |
| `PATCH` | `/api/v1/notifications/{id}/read` | Idempotent acknowledge |
| `PATCH` | `/api/v1/notifications/{id}/unread` | Idempotent restore |

Cross-tenant identifiers and purged rows both return `404`; a missing `INSTANCE_READ` authority
returns `403` and an anonymous call `401`. The transitions are single conditional `UPDATE`
statements (`read_at IS NULL` / `read_at IS NOT NULL`), so concurrent read/unread calls converge on
one state without version conflicts.

### Metrics

| Metric | Type | Tags | Use |
|---|---|---|---|
| `awap.notification.unread.count` | gauge | `tenant` | Last observed unread count per tenant |
| `awap.notification.read.latency` | timer | `operation` (`read`/`unread`), `result` (`changed`/`unchanged`/`not_found`/`error`) | Read/unread latency and outcome mix |
| `awap.notification.read.transitions` | counter | `operation`, `result` | Transition volume, including no-op retries |

PromQL examples:

```promql
# Tenants with a growing unread backlog (candidate for a soft alert).
max by (tenant) (awap_notification_unread_count) > 500
# Share of read calls that were no-ops (client retries / double clicks).
sum(rate(awap_notification_read_transitions_total{operation="read",result="unchanged"}[5m]))
  / sum(rate(awap_notification_read_transitions_total{operation="read"}[5m]))
# p95 read latency.
histogram_quantile(0.95,
  sum(rate(awap_notification_read_latency_seconds_bucket[5m])) by (le, operation))
```

The gauge is published from the last `unreadCount`/transition observation instead of a scrape-time
query: the Prometheus scrape has no tenant context, so a database read under row-level security
would report zero for every tenant.

### Retention

`NotificationDeliveryPurgeScheduler` (03:30 daily) runs two independent windows:

| Window | Property | Default | Deletes |
|---|---|---|---|
| Read | `awap.notification.read-retention-days` / `NOTIFICATION_READ_RETENTION_DAYS` | 30 days | Rows whose `read_at` is older than the cutoff |
| Unread | `awap.notification.unread-retention-days` / `NOTIFICATION_UNREAD_RETENTION_DAYS` | 180 days | Rows that are still unread and whose `created_at` is older than the cutoff |

`NOTIFICATION_RETENTION_DAYS` is kept for compatibility but no longer drives the purge. Because the
scheduler runs without a tenant context, the purge uses the `SECURITY DEFINER` helpers
`awap_purge_read_notifications(cutoff)` and `awap_purge_unread_notifications(cutoff)` with a pinned
`search_path`; a plain `DELETE` as `awap_app` would be filtered to zero rows by RLS. The unread
window must never be configured shorter than the read window — the policy rejects it at startup.

### Rollback and data migration

V46 is additive (`read_at`, `version`, two indexes, two helper functions), so an application
rollback to the previous image keeps working: the old code simply ignores the new columns. Rolling
the schema back requires dropping the functions, the indexes and then `read_at` — that discards all
acknowledgement history, so prefer a restore from a pre-V46 backup when acknowledgement state must
be preserved. Purging can be paused without a deploy by setting both retention windows to a very
large value and restarting the scheduler.

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
