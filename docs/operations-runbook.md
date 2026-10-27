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

## O8 — Outbox relay mode and the JPA fallback (#122)

> O7 is reserved for outbox claim reconciliation; this section is referenced as "runbook O8" from
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
