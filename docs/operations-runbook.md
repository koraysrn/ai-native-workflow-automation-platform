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
other silently. Locales are canonicalized to `language` or `language-REGION` (for example `en` or
`en-US`), so `en-us` from a client matches the stored `en-US` row. Built-in EN/TR templates are
seeded by `V47__notification_templates.sql` for tenants that exist when the migration runs; new
tenants rely on the code fallback in `NotificationTemplateRenderer`, so a missing row never blocks a
delivery.

Resolution order for a delivery: tenant template for the requested locale → tenant template for
`en` → built-in code default → the raw template key. Archived templates are excluded, which makes
archive a safe rollback to the built-in default. Cached entries live for
`awap.notification.template-cache-ttl-seconds` (default 60s), so a write on another replica is
picked up without a restart; writes on the same replica invalidate the entry immediately.

`awap.notification.template.render.failures` increments for malformed placeholders, unknown
placeholders and invalid variables JSON. `AwapNotificationTemplateRenderFailures` fires when the
5-minute increase is non-zero; the message identifies the template key and locale in the warning
log without echoing template bodies or variables.

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

## O11 — Notification retries (issue #119)

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

## O12 — Notification de-duplication (TTL + business key)

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

## O13 — Outbox claim reconciliation (#121)

Since V44 a claim is a **lease**, not a publication: the relay sets `claimed_at` + `claim_token` and
leaves `published = false`, sends to Kafka, and only then sets `published = true` with `published_at`
and clears the claim. A crash between the claim and the send therefore leaves a recoverable row
instead of a silently lost event.

**Configuration** ([`application.yml`](../backend/identity/src/main/resources/application.yml)):

| Property | Default | Meaning |
|---|---|---|
| `awap.outbox.claim-lease` | `60s` | Lease length. Must stay longer than the relay's 10s send timeout; the application refuses to start otherwise. |
| `awap.outbox.max-publish-attempts` | `5` | After this many failures the row is quarantined (no longer leased, reported loudly). |
| `awap.outbox.reconciliation.interval-ms` | `60000` | Reconciliation cadence. |
| `awap.outbox.reconciliation.batch-size` | `1000` | Rows handled per reconciliation run. |

**Metrics / alerts** ([`alert-rules.yml`](../infra/prometheus/alert-rules.yml)):

- `awap_outbox_stuck_total{reason="stale_claim"}` — increment once per claim released by reconciliation.
- `awap_outbox_stuck_total{reason="legacy_published_without_timestamp"}` — legacy rows seen in a run.
- `awap_outbox_claim_age_seconds{_sum,_count,_max}` — age of the released claim.
- `awap_outbox_publish_failures_total{reason}` — `send_failed`, `corrupted_payload`, `claim_lost`.
- `awap_outbox_exhausted` — quarantined rows needing operator action.
- `awap_outbox_reconciliation_failures` — consecutive failed reconciliation runs (the schedule keeps running).

### A stale claim appeared (`reason="stale_claim"`)

1. Confirm the relay pods are healthy; a restart or OOM kill inside the send window is the usual cause.
   Reconciliation re-queues the row automatically, and the idempotent producer plus the `aggregate_id`
   record key keep the republish safe.
2. If the counter keeps rising, check `awap_outbox_publish_failures_total{reason="send_failed"}` and
   the broker health before tuning the lease.

### Legacy rows: `published = true` without `published_at`

These are rows stranded by the pre-V44 relay. The scheduler reports them and **never** republishes
them, because a blind replay can duplicate a business transaction that did reach Kafka.

```sql
SELECT id, tenant_id, event_type, created_at, publish_attempts
FROM outbox_events
WHERE published = true AND published_at IS NULL
ORDER BY created_at;
```

1. Check the Kafka topic for the `aggregate_id` key (the key equals `outbox_events.aggregate_id`) and
   the event payload before acting.
2. If the record **is** in Kafka: close the row without republishing, and record the audit note in the
   incident ticket.
   ```sql
   UPDATE outbox_events
   SET published_at = now()
   WHERE id = '<id>' AND published = true AND published_at IS NULL;
   ```
3. If the record is **missing**: re-queue it for the relay (the send is idempotent, so a duplicate is
   harmless for the broker) and keep the audit trail in the ticket.
   ```sql
   UPDATE outbox_events
   SET published = false, published_at = NULL, claimed_at = NULL, claim_token = NULL
   WHERE id = '<id>' AND published = true AND published_at IS NULL;
   ```
4. Do this **before** the retention purge (default `OUTBOX_RETENTION_DAYS=7`) removes the row.

### Quarantined rows (`awap_outbox_exhausted > 0`)

```sql
SELECT id, tenant_id, event_type, publish_attempts, created_at
FROM outbox_events
WHERE published = false AND publish_attempts >= 5
ORDER BY created_at;
```

Inspect `payload_json`; a corrupted payload is the usual cause (the relay flags it and stops retrying
after `awap.outbox.max-publish-attempts`). Fix or archive the row, then reset the counter:

```sql
UPDATE outbox_events SET publish_attempts = 0 WHERE id = '<id>' AND published = false;
```

### Rollback

The V44 columns are additive and nullable (except `publish_attempts DEFAULT 0`), so a rollback is a
redeploy of the previous image — **do not drop the columns** in production: the previous release
ignores them. Releasing every lease before shutting down avoids waiting for the lease to expire:

```sql
UPDATE outbox_events SET claimed_at = NULL, claim_token = NULL WHERE published = false;
```

## O14 — Outbox relay mode and the JPA fallback (#122)

> O7 is reserved for outbox claim reconciliation; this section is referenced as "runbook O14" from
> the code (`OutboxRelayConfigurationGuard`, `JpaOutboxRelaySource`, the health indicator and
> `application.yml`).

The relay has exactly two paths, selected by `awap.outbox.datasource.enabled`
(`OUTBOX_SEPARATE_POOL`):

| Mode | Selection | How it reads the outbox | Safe in production |
|---|---|---|---|
| `jdbc` | `OUTBOX_SEPARATE_POOL=true` (**default**) | Dedicated Hikari pool (`awap.outbox.datasource.name`, default `awap-outbox-relay`) calling the V37 `SECURITY DEFINER` claim/release functions. They run as the migration owner with a pinned `search_path`, so the relay sees every tenant even though `awap_app` is bound by `FORCE ROW LEVEL SECURITY`. | **Yes** |
| `jpa` | `OUTBOX_SEPARATE_POOL=false` | The shared request-serving pool through Spring Data JPA. The relay has **no tenant context**, so `current_setting('awap.tenant_id', true)` is null and the RLS policies filter every `outbox_events` row out. | **No — dev/test only** |

Both paths run through the same `OutboxRelaySource` contract and the same publish loop, so the
failure accounting and the observability below are identical.

### Fallback restriction

`OUTBOX_SEPARATE_POOL=false` used to be a silent trap: on PostgreSQL with RLS the claim query
returned nothing and event delivery simply stopped, with no error and no metric.

- **prod / staging:** the application refuses to start. `OutboxRelayConfigurationGuard` throws a
  bean-creation error naming the property, the profiles and the RLS cause. There is no override; the
  dedicated pool is the only supported production configuration.
- **dev / test / no profile:** the fallback is allowed (single-connection databases without RLS
  policies are the normal case) and a `WARN` line states the restriction. The `outboxRelay` health
  component also reports `rlsSafe=false`.
- A malformed value (`OUTBOX_SEPARATE_POOL=yes`) or an invalid pool name
  (`awap.outbox.datasource.name` must match `[a-z][a-z0-9-]{0,62}`) fails startup in every profile,
  so a typo cannot select an unexpected path.

If you need the fallback on a PostgreSQL database that has RLS enabled (for example a shared dev
environment), scope the connection to a tenant or, better, leave the dedicated pool on: it is a
separate two-to-four connection pool and does not steal capacity from requests.

### Observability

- `GET /actuator/health` → component `outboxRelay`:
  - `UNKNOWN` until the relay completed its first run (a fresh instance has not proven anything),
  - `UP` while the last run published without a failure,
  - `DOWN` as soon as a claim or a Kafka send fails; the next clean run clears it.
  Details: `mode` (`jdbc`/`jpa`), `relayDatasource`, `rlsSafe`, `lastSuccessfulPublish`
  (`none` before the first publish), `lastRun`, `publishedEvents`, `consecutiveFailures`,
  `lastFailureReason`, `failure` timestamp and, for the fallback, a `warning`.
- `awap_outbox_relay_mode{mode="jdbc"|"jpa"}` — 1/0 gauge, both series always present. A dashboard
  or alert can use `awap_outbox_relay_mode{mode="jpa"} == 1`; in prod that expression must never
  match.
- `awap_outbox_publish_failures_total{mode,reason}` — `reason="send_failed"` (Kafka rejected or timed
  out, the claim was released for a retry) or `reason="claim_failed"` (the claim query itself failed,
  for example the pool or the database is down).

### Triage: `outboxRelay` is DOWN

1. Read the details: `mode`, `lastFailureReason` and `lastSuccessfulPublish`. A stale
   `lastSuccessfulPublish` with `mode="jpa"` in a PostgreSQL environment means RLS is hiding the rows.
2. `reason="send_failed"`: check the broker (`KAFKA_BOOTSTRAP_SERVERS`, topic health) and
   `awap_outbox_publish_failures_total{mode,reason="send_failed"}`. The rows stay unpublished and are
   retried on the next tick, so no event is lost while the broker is unreachable.
3. `reason="claim_failed"`: check the outbox pool saturation (`hikaricp_connections` for
   `awap-outbox-relay`) and database availability.
4. If the health component reports `rlsSafe=false` in a production environment, the deployment is
   misconfigured even if the pod started: set `OUTBOX_SEPARATE_POOL=true`, restart and confirm
   `awap_outbox_relay_mode{mode="jdbc"} == 1`.
5. Confirm the fix by checking that `lastSuccessfulPublish` advances and the component returns `UP`.

### Rollback

This change adds no migration and no schema element. Rolling back is a redeploy of the previous
image (`kubectl rollout undo`); the previous release ignores `awap.outbox.datasource.name`,
`awap.outbox.relay-interval-ms` and `awap.outbox.relay-initial-delay-ms` and falls back to the
hardcoded pool name and five second cadence. The relay mode metric and the health component simply
disappear from the actuator output.

When rolling back a production incident caused by the fallback, keep the guard in place and fix the
configuration instead: `OUTBOX_SEPARATE_POOL=true` is the documented, RLS-safe path.

## O15 — Legacy awap.dlq dual-consume and removal (#123)

The DLQ listener consumes every `<source>.dlq` topic and, for one release, the legacy `awap.dlq`
topic as well. Older deployments that still publish to `awap.dlq` are therefore no longer invisible.
The subscription pattern is anchored, so unrelated topics such as `not-a-dlq-topic` never match.

- Flag: `awap.kafka.legacy-dlq-enabled` (`KAFKA_LEGACY_DLQ_ENABLED`), default `true`.
- Legacy topic: `awap.kafka.legacy-dlq-topic` (`KAFKA_LEGACY_DLQ_TOPIC`), default `awap.dlq`.
- Metrics: `awap_dlq_messages_total{topic,legacy}` (bounded `topic` values, unknown DLQ topics are
  folded into `other`), `awap_dlq_legacy_messages_total`, `awap_dlq_quarantined_total{topic}` and
  `awap_dlq_duplicates_total{topic}`. Alerts: `AwapLegacyDlqMessages`,
  `AwapDlqQuarantine` and the existing backlog warnings.
- The handler is idempotent (per topic-partition offset) and has no producer dependency: a
  dead-letter record is never republished to its source topic or to another `.dlq` topic.
- Malformed payloads (blank, or JSON that cannot be parsed) are appended to
  `awap.kafka.dlq-quarantine-path` (`DLQ_QUARANTINE_PATH`, default
  `target/dlq-quarantine/legacy-dlq-quarantine.jsonl`) and counted; the listener keeps running.
- Large backlogs are drained with `awap.kafka.max-poll-records` (`KAFKA_MAX_POLL_RECORDS`, default
  50), and consumer reconnect backoff keeps the listener retrying when Kafka is unavailable at
  startup.

### Copying the legacy backlog

Build the jar, dry-run, then run twice. The checkpoint makes the second run a no-op:

```sh
backend/mvnw -B -pl identity -am -DskipTests package
scripts/dlq-legacy-copy.sh --dry-run --bootstrap-servers=localhost:9092
scripts/dlq-legacy-copy.sh --bootstrap-servers=localhost:9092
scripts/dlq-legacy-copy.sh --bootstrap-servers=localhost:9092
```

The script copies each source record to `<original-topic>.dlq` when the
`kafka_dlt-original-topic`/`x-original-topic` header is present, and to `awap.legacy.dlq` otherwise.
It never writes to `awap.dlq`. The checkpoint (default
`target/legacy-dlq-copy-state.properties`) stores the highest copied offset per partition; it is
written only after `producer.flush()`, so an interrupted run loses nothing (at worst it recopies the
current batch, which the idempotent handler tolerates). `--max-messages` bounds one invocation.

### Deprecation window and removal step

- Window: the dual-consume flag stays `true` for one release (the release containing issue #123 and
  the following patch/minor). Do not set it to `false` on a half-migrated environment.
- Before removal, run `scripts/dlq-legacy-copy.sh` to drain the backlog and confirm
  `increase(awap_dlq_legacy_messages_total[7d]) == 0` for at least 14 days.
- Removal PR (one release later): set `KAFKA_LEGACY_DLQ_ENABLED=false` in staging, verify the
  per-topic DLQ path still consumes and counts, then delete `DlqTopicSubscription`, the
  `awap.kafka.legacy-dlq-*` properties and the legacy-specific runbook steps. No database migration
  is involved, so rollback is simply re-enabling the flag in the previous image.
- Do not delete the `awap.dlq` Kafka topic until the retention window of the oldest supported
  deployment has passed.

## O16 — Analytics OpenSearch sink (#124)

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

## O17 — Tenant usage metering and quotas (#125)

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

## O18 — Saga compensation (issue #133)

**Purpose.** `saga_instances` / `saga_steps` make the workflow engine the coordinator for
multi-step external integrations. A `WEBHOOK` node with a `compensation` block, and every
SUB_PROCESS child that completed successfully, becomes a saga step. If a later node fails, the
engine walks the steps in reverse `step_order` and executes their compensations.

**Automatic flow.** Workflow failure moves the saga `RUNNING -> COMPENSATING`; each successful
compensation becomes `COMPENSATED`; the saga ends `COMPENSATED`. A compensation that exhausts
`maxAttempts` becomes `DLQ`, the saga becomes `FAILED`, and a `SAGA_COMPENSATION_DLQ` outbox event
is published to `workflow.saga.events.dlq` (consumed by the normal per-topic DLQ listener).

**Crash recovery.** The scheduler claims `COMPENSATING` sagas through
`awap_claim_saga_compensations` (`FOR UPDATE SKIP LOCKED`, lease token, cross-tenant). A stale lease
is taken over after `awap.saga.lease-seconds` (default 30); the first uncompensated step is resumed.
The scheduler is disabled when `awap.saga.scheduler-enabled=false` and can then be driven by the
manual API.

**Manual intervention.**
- Inspect: `GET /api/v1/workflow-instances/{id}/saga` (`INSTANCE_READ`).
- Resume all dead-lettered steps: `POST /api/v1/workflow-instances/{id}/saga/compensate`
  (`INSTANCE_MANAGE`) after the ERP/CRM endpoint is healthy.
- Retry one step: `POST /api/v1/workflow-instances/{id}/saga/steps/{stepId}/retry`. Reverse order is
  enforced (`409 AWAP-ENG-0015`).
- Cross-tenant access returns `404 AWAP-ENG-0013`; a saga with no recorded step produces the same
  404, so tenants cannot distinguish "missing" from "someone else's".

**Tuning.** Per-step values live in the node's `compensation` block (`maxAttempts`, `retryBackoffMs`,
`timeoutSeconds`). Defaults are `SAGA_MAX_ATTEMPTS` (3), `SAGA_RETRY_BACKOFF_MS` (1000) and
`SAGA_TIMEOUT_SECONDS` (30). The scheduler uses `SAGA_SCHEDULER_INTERVAL_MS`, `SAGA_LEASE_SECONDS`
and `SAGA_BATCH_SIZE`.

**Alerts.** `AwapSagaCompensationFailures` (critical, any failed step) and
`AwapSagaCompensationRetriesHigh` (warning, retry storm). Metrics: `awap.saga.steps{outcome}`,
`awap.saga.compensation.retries`, `awap.saga.duration{status}`.

**Rollback.** The feature is additive (V49 creates new tables, policies and one function). Keep the
tables but set `SAGA_SCHEDULER_ENABLED=false` and stop sending `compensation` blocks; existing
`FAILED` sagas remain inspectable and can be finished manually. No down-migration is required.
