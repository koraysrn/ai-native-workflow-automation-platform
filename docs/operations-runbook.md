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
error rate > 5%, p95 latency > 2s, DLQ backlog (warning/critical), instance-down, node timeouts
(warning), terminal node timeout failures (critical), abandoned timeout workers (critical) and
timeout executor rejections (warning).

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
