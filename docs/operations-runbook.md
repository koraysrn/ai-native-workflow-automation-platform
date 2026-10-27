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

## O7 — Quarantine uploads, promotion and infected files (#118)

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
