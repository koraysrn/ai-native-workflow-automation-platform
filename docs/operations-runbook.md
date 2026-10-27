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
