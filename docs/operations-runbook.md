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

## O7 — Legacy `awap.dlq` dual-consume and removal

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
