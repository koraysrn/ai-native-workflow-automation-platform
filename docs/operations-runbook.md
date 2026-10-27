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

## O7 — Deterministic replay (issue #116)

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
