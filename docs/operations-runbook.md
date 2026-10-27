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

## O7 — Outbox claim reconciliation (#121)

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
