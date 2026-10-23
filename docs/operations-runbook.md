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
Verify with the OTel collector at `http://localhost:4318/v1/traces`.

## O3 — SLO alerts

Defined in [`infra/prometheus/alert-rules.yml`](../infra/prometheus/alert-rules.yml):
error rate > 5%, p95 latency > 2s, DLQ backlog (warning/critical) and instance-down.

## O4 — Backup & disaster recovery

- PostgreSQL: nightly logical dumps (`pg_dump`) + WAL archiving; RPO 24h, RTO 4h.
- MinIO: object versioning is enabled by `FileService`; replicate to a secondary bucket/region.
- Restore drill: restore the latest dump into a fresh container and verify Flyway
  (`flyway_schema_history`) matches the schema version before serving traffic.

> TODO: automate the pg_dump cron and the MinIO replication policy; codify the restore drill as a
> scripted runbook step with a measured RTO.
