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
| `AwapNodeTimeouts` | node timeout count > 10 in 10m | warning | Inspect `NODE_TIMED_OUT` execution logs to find the node type before re-driving |
| `AwapNodeTimeoutFailures` | terminal timeout failures > 3 in 10m | critical | Check the enforcement mode, fix the slow node, then re-drive failed instances |
| `AwapNodeTimeoutAbandonedWorkers` | abandoned timeout workers > 0 | critical | A node is swallowing interrupts; find the type in the warnings and restart |
| `AwapNodeTimeoutExecutorRejections` | executor rejections > 0 in 5m | warning | Raise `NODE_TIMEOUT_WORKER_THREADS` / `NODE_TIMEOUT_QUEUE_CAPACITY` |

Dashboard: `AWAP platform overview` (`infra/grafana/provisioning/dashboards/awap.json`) with
`tenant` and `channel` template variables. Panel/metric mapping:
[`docs/observability-metrics.md`](../docs/observability-metrics.md).

Dashboard and alert definitions are validated in CI: the JSON is parsed and every referenced metric
must exist in [`infra/grafana/metric-catalog.json`](../infra/grafana/metric-catalog.json), and
`promtool check rules` validates the alert file.

## O3.1 — Node timeouts

Which node types have a hard cancellation guarantee and which are only measured is documented in
[`node-timeouts.md`](../docs/node-timeouts.md); the rationale is
[ADR 0004](../docs/adr/0004-node-timeout-enforcement.md).

Operating procedure for `AwapNodeTimeouts` / `AwapNodeTimeoutFailures`:

1. Identify the node type: `topk(5, sum by (type) (increase(awap_node_timeout_total[10m])))`.
2. Compare with the budget and the real latency:
   `histogram_quantile(0.99, sum by (le, type) (rate(awap_node_duration_seconds_bucket[10m])))`.
3. Inspect affected instances: `execution_logs` rows with `action = 'NODE_TIMED_OUT'` carry
   `enforcement`, `timeoutSeconds`, `elapsedMs`, `attempt`, `maxAttempts` and `retryScheduled`.
   Only `enforcement = 'ENFORCED'` means the work was actually cancelled; `BEST_EFFORT` means the
   work completed and was then classified as a timeout.
4. If `awap_node_timeout_rejected_total` grows, raise
   `NODE_TIMEOUT_WORKER_THREADS` / `NODE_TIMEOUT_QUEUE_CAPACITY` (bounded — the pool is a
   safety valve, not a throughput tool).
5. If `awap_node_timeout_abandoned` stays above zero, a node is swallowing interrupts. Find the
   node type from the warnings ("Node timeout worker did not stop immediately after cancel"),
   fix the code to honour `InterruptedException`, and restart the instance if the thread never
   exits.
6. Re-drive failures with the workflow retry/recover endpoints once the cause is fixed; both
   `FAILED` and `TIMED_OUT` executions are re-drivable.

Daemon workers idle out after 30s and the executor is drained on shutdown, so a normal restart
does not leave `awap-node-timeout-*` threads behind.

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

## O9 — Deterministic replay (issue #116)

### Scope
`POST /api/v1/workflow-instances/{id}/replay?dryRun=true` folds the instance's ordered
`execution_logs` (`createdAt` then `id` tie-breaker) into an immutable `ReplayState`: instance
state, current node, per-node status, parallel branch token/merge counters and a **redacted** output
summary (length, SHA-256 prefix and JSON key names only — never values). The projection is compared
with the persisted `workflow_instances` and `node_executions` rows. Every run, dry or real, appends
one row to `replay_runs` (tenant, instance, dry-run, result, divergence flag/detail, state hash,
rows, pages, duration, requester); the table is RLS-protected per tenant.

Replay is bounded: `awap.replay.max-rows` (default 100000), `awap.replay.page-size` (default 500,
only one page is held in memory) and `awap.replay.timeout` (default 30s). Exceeding the row cap
returns 413 (`AWAP-ENG-0006`), exceeding the timeout 422 (`AWAP-ENG-0007`) and an unreadable log 503
(`AWAP-ENG-0008`) — all controlled errors, never an OOM.

### External-call suppression
Replay never performs side effects. `WEBHOOK*` and notification actions are folded from recorded
results only; the projector holds no HTTP client, outbox publisher or task service. A webhook
completion without a recorded result is reported as `EXTERNAL_RESULT_MISSING` divergence instead of
being re-invoked. Dry-run writes nothing at all except the audit row (no outbox, instance or node
write).

### Divergence investigation
1. `GET /api/v1/workflow-instances/{id}/logs` and compare against the report's `divergences`.
2. Look at the newest `replay_runs` row for the tenant/instance: `result = DIVERGED`,
   `divergence_detail` lists `TYPE@nodeId` (e.g. `MISSING_NODE_COMPLETION@approval`).
3. Interpret the type: `MISSING_NODE_COMPLETION` = persisted node completed without a log action;
   `UNKNOWN_ACTION` = a newer writer emits an action this deployment does not understand (deploy the
   matching version, never skip it); `MALFORMED_PAYLOAD` = the recorded output is not JSON;
   `OUT_OF_ORDER_LOG` = clock/id collision; `BRANCH_TOKEN_MISMATCH`/`BRANCH_MERGE_MISMATCH` =
   parallel counters disagree with `node_executions`.
4. `AwapReplayDivergence` alert (`awap_replay_divergences_total`) fires on any divergence — treat it
   as an incident, preserve the audit row and the log window, and do not delete log rows.
5. A real (non-dry-run) replay refuses to run on divergence: it returns 409 `AWAP-ENG-0004`, a
   second concurrent replay returns 409 `AWAP-ENG-0005`, and a concurrent instance update fails the
   optimistic version check with the same code.

### Manual republish prohibition
Do **not** "repair" an instance by editing `execution_logs`, `node_executions` or the instance state
by hand, and do not republish the workflow definition to force a replay. Republishing changes the
definition/version the log was written against, destroys the audit trail and can create duplicate
outbox side effects. If the state is irrecoverable, open an incident and follow O4 (restore) or fix
the writer so future logs are complete.

### Rollback
Replay is additive: revert the API/consumer and stop calling the endpoint. `replay_runs` is
append-only and can stay in place (it has no foreign keys into the engine tables); drop it only
through a new Flyway migration, never by hand.

## O10 — Quarantine uploads, promotion and infected files (#118)

Uploads never land in `awap-files` directly. The API issues a presigned PUT to `awap-quarantine`
only, with a maximum TTL of 5 minutes. `FileScanScheduler` streams the object to ClamAV with an
8 KB buffer and walks the `FileRecord` state machine:

```text
QUARANTINED -> SCANNING -> CLEAN -> PROMOTED
                     \-> INFECTED (terminal)
                     \-> SCAN_FAILED -> SCANNING (retry)
```

Download is a presigned GET on `awap-files` **only** for `PROMOTED`. INFECTED returns 403,
`SCAN_FAILED` returns 503, and `QUARANTINED`/`SCANNING`/`CLEAN` return 409.

### Infected file handling

1. Confirm the alert `AwapFileInfected` in Prometheus or inspect
   `awap_file_infected_total` / the `file_scan_audit` table (`event = 'INFECTED'`).
2. The default policy (`FILE_INFECTED_POLICY=DELETE`) deletes the quarantine object. If the policy
   is `RETAIN`, the object stays in `awap-quarantine` under `tenantId/fileId/name` for forensics;
   never copy it to `awap-files`.
3. The file record is terminal `INFECTED`; it is never promoted, and a new upload of the same
   content is scanned again and rejected consistently.
4. To purge a retained sample manually (object-lock governance mode):
   `mc rm --bypass-governance --recursive awap/awap-quarantine/<tenantId>/<fileId>/`
5. Record the incident and do not disable scanning (`CLAMAV_ENABLED`) to "unblock" a file.

### Promotion failure recovery

- `AwapFilePromoteFailures` fires when `awap_file_promote_fail_total` increases.
- A failed copy leaves the record `CLEAN` and the quarantine source intact; the scheduler retries
  on the next tick. A copy that succeeded but whose source delete failed is also retried: the
  deterministic destination key `tenantId/fileId/name` makes the repeated copy idempotent and
  exactly one promoted object exists.
- If the source disappeared but the promoted object exists, the scheduler repairs the record
  (`recovered promotion` audit row) instead of losing it.
- Verify MinIO health (`/minio/health/live`), then inspect:
  `SELECT id, status, scan_attempts, last_scan_error FROM file_records WHERE status IN ('CLEAN','SCAN_FAILED');`
- If a `SCAN_FAILED` record reached `FILE_MAX_SCAN_ATTEMPTS`, fix clamd and re-upload the file;
  the record stays fail-closed (download 503) rather than being served unscanned.

### Bucket configuration

`MinioFileObjectStore` provisions `awap-quarantine` with object lock (governance retention),
versioning and a lifecycle rule (expire quarantine objects after
`FILE_QUARANTINE_RETENTION_DAYS`, abort incomplete multipart uploads after one day). `awap-files`
is versioned with `FILE_PROMOTED_RETENTION_DAYS`. Existing buckets are not recreated; object lock
cannot be enabled in place, so a pre-existing quarantine bucket must be recreated by ops.
