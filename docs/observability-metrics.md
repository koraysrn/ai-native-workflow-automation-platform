# Observability metric map

Prometheus names below are the Micrometer meter names with `.` → `_`; counters gain `_total`,
timers/histograms gain `_seconds_bucket`/`_seconds_count`/`_seconds_sum`. The machine-readable list
lives in [`infra/grafana/metric-catalog.json`](../infra/grafana/metric-catalog.json) and is enforced
by the dashboard/alert tests in CI.

| Micrometer meter | Prometheus name | Emitted by | Dashboard panel | Alert |
|---|---|---|---|---|
| `awap.workflow.success` | `awap_workflow_success_total` | `AnalyticsService` | Workflow success vs failure rate, Workflow failure ratio | `AwapWorkflowFailureRateHigh` |
| `awap.workflow.failure` | `awap_workflow_failure_total` | `AnalyticsService` | same | `AwapWorkflowFailureRateHigh` |
| `awap.task.completed` | `awap_task_completed_total` | `AnalyticsService` | Task completion rate | – |
| `awap.task.decisions` | `awap_task_decisions_total` | `TaskService` | Task decisions | – |
| `awap.process.duration` | `awap_process_duration_seconds_*` | `AnalyticsService` | Workflow process duration p95 | – |
| `awap.sla.breaches` | `awap_sla_breaches_total` | `AnalyticsService` | SLA breaches (1h) | – |
| `awap.notification.deliveries` | `awap_notification_deliveries_total` | `NotificationService` | Notification deliveries and failures | – |
| `awap.notification.attempts` | `awap_notification_attempts_total` | `NotificationService` | Notification deliveries and failures | – |
| `awap.notification.dispatch.latency` | `awap_notification_dispatch_latency_seconds_*` | `NotificationService` | Notification retry lag and dispatch latency | – |
| `awap.notification.retry.lag` | `awap_notification_retry_lag_seconds_*` | `NotificationService` | Notification retry lag and dispatch latency | `AwapNotificationRetryLagHigh` |
| `awap.saga.steps` | `awap_saga_steps_total` | `SagaMetrics` | – (saga panel/API) | `AwapSagaCompensationFailures` |
| `awap.saga.compensation.retries` | `awap_saga_compensation_retries_total` | `SagaMetrics` | – (saga panel/API) | `AwapSagaCompensationRetriesHigh` |
| `awap.saga.duration` | `awap_saga_duration_seconds_*` | `SagaMetrics` | – (saga panel/API) | – |
| `awap.workflow.expression.validations` | `awap_workflow_expression_validations_total` | `WorkflowExpressionValidationService` | – (designer inline feedback) | `AwapWorkflowExpressionValidationFailures` |
| `awap.outbox.pending` | `awap_outbox_pending` | `OutboxRelayService` (gauge) | Outbox backlog, relay rate and DLQ | `AwapOutboxBacklogStuck` |
| `awap.outbox.events` | `awap_outbox_events_total` | `OutboxRelayService` | Outbox backlog, relay rate and DLQ | – |
| `awap.dlq.messages` | `awap_dlq_messages_total` | `DlqConsumer` | Outbox backlog, relay rate and DLQ | `AwapWebhookDeadGrowth` |
| `awap.document.sse.connections` | `awap_document_sse_connections` | `DocumentScanMetrics` (gauge) | Document scan stream health | `AwapDocumentSseConnectionsHigh` |
| `awap.document.sse.connected` | `awap_document_sse_connected_total` | `DocumentScanMetrics` | Document scan stream health | – |
| `awap.document.sse.disconnected` | `awap_document_sse_disconnected_total` | `DocumentScanMetrics` | Document scan stream health | – |
| `awap.document.sse.events` | `awap_document_sse_events_total` | `DocumentScanMetrics` | Document scan stream health | – |
| `awap.document.scan.status.latency` | `awap_document_scan_status_latency_seconds_*` | `DocumentScanMetrics` | Document scan status latency | – |
| HTTP observations | `http_server_requests_seconds_count` | Spring Boot | Outbox backlog, relay rate and DLQ (5xx) | `AwapHighErrorRate`, `AwapHighLatency` |

## Live validation (issue #160)

The dashboard JSON, metric catalog and alert file were previously validated by parsing only
(`node:test` + `promtool check rules`). Issue #160 adds execution of the real PromQL against a
running Prometheus.

### Local Prometheus

`infra/docker-compose.yml` now provisions five pieces needed for an end-to-end check:

| Component | Role |
|---|---|
| `backend` | Exposes `/actuator/prometheus`; scraped as job `awap-backend`. |
| `postgres-exporter` | Exposes the replication-slot series; scraped as job `awap-postgres-exporter`. |
| `prometheus` | Mounts `prometheus.yml`, `alert-rules.yml` and `cdc-alert-rules.yml` (previously the rule files were not mounted, so a local Prometheus loaded no rules at all). |
| `grafana` | Provisions `awap.json` against the `prometheus` datasource. |

Start the stack and confirm both targets are `UP`:

```bash
docker compose -f infra/docker-compose.yml up -d postgres postgres-exporter prometheus backend
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[].labels'
```

`pg_replication_slots_active` and `pg_replication_slot_pg_wal_lsn_diff` only appear once the
Debezium connector has created the `awap_outbox` slot; until then the CDC rules stay silent by
design and the series are simply absent.

### Query execution script

[`infra/prometheus/validate-live-queries.mjs`](../infra/prometheus/validate-live-queries.mjs)
executes every target in the provisioned dashboard. Without arguments it starts
[`synthetic-metrics.mjs`](../infra/prometheus/synthetic-metrics.mjs) (one representative series
for every metric the panels and alerts reference) and a throwaway `prom/prometheus:v2.55.0`
container; with `PROMETHEUS_LIVE_URL` it checks an already-running Prometheus instead.

```bash
node infra/prometheus/validate-live-queries.mjs
# or, against the Compose Prometheus after real traffic:
PROMETHEUS_LIVE_URL=http://localhost:9090 node infra/prometheus/validate-live-queries.mjs
```

It fails unless:

- `up{job="awap-backend"} == 1` and `up{job="awap-postgres-exporter"} == 1`,
- both CDC exporter series exist,
- every panel target returns at least one series with the `$tenant`/`$channel` variables set to
  `.*` (Grafana's "All"),
- every tenant/channel-filtered target returns the explicitly expected empty result for the
  sentinel `__awap_no_data__` (the "tenant with no data" case from #130).

Panel expectations are declared in
[`infra/grafana/query-expectations.json`](../infra/grafana/query-expectations.json); a new panel
must be added there before the script (and the static `node:test` suite) accepts it.

### Alert firing tests

[`infra/prometheus/test/alert-rules.test.yml`](../infra/prometheus/test/alert-rules.test.yml)
runs the real rule file through `promtool test rules`, covering both the firing and the quiet case
for the four alerts added in #130:

```bash
docker run --rm -v "$PWD/infra/prometheus:/etc/prometheus" \
  --entrypoint promtool prom/prometheus:v2.55.0 \
  test rules /etc/prometheus/test/alert-rules.test.yml
```

The suite asserts the exact alert labels and the rendered annotations (including `runbook_url`),
so a broken template or a removed runbook link fails CI as well.

### Findings

1. **Instance-down alert watched a job that did not exist.** `AwapInstanceDown` used
   `up{job="awap-identity"}`, but `prometheus.yml` scrapes the backend as `awap-backend`. The
   alert could never fire. It now watches `awap-backend`; a promtool case covers it.
2. **Local Prometheus never loaded the alert rules.** Only `prometheus.yml` was bind-mounted, so
   `rule_files: [alert-rules.yml, cdc-alert-rules.yml]` referenced files that were absent inside
   the container. Both files are mounted now.
3. **No CDC exporter existed.** `cdc-alert-rules.yml` needed `postgres_exporter`, which was not
   part of the stack. A `postgres-exporter` service with `--collector.replication_slot` is
   provisioned and scraped as `awap-postgres-exporter`.
4. **Every panel query returns data with representative series.** The script resolves all nine
   panels (twenty-four target queries) plus the sentinel-empty variants — 29 checks in total.
5. **Counter resets stay safe.** Every dashboard target uses `rate()`/`increase()`, so the
   restart-induced counter reset test from #130 is covered by construction.
6. **The retry-lag panel is legitimately empty when nothing retried.** The synthetic scenario
   supplies retry observations, so the panel is asserted as `series`; the sentinel variant proves
   the empty path renders without an error. A real stack with zero retries shows "No data" rather
   than an error panel.

## Notes

- Tenant labels: workflow counters carry `tenant`; the dashboard's `tenant` template variable is
  `includeAll` so operators can narrow to a single tenant. Cardinality is bounded by the tenant
  count (one series per tenant), not by request volume.
- Counter resets (pod restarts) are handled because every query uses `rate()`/`increase()`.
- Notification retries are only recorded when `attempts > 1`, so the retry-lag panel stays empty
  when nothing needs retrying instead of showing zeros.
- `awap_outbox_pending` is a gauge with an `AtomicLong` registered once; Micrometer re-reads it on
  every scrape, so the value tracks the latest relay tick rather than a stale number.
- Empty metrics render as "No data" in Grafana; the panel JSON does not set `noValue` overrides, so
  an empty tenant never produces a red error panel.
- `awap_document_sse_connections` is a gauge over an `AtomicInteger` held by
  `DocumentScanMetrics`; it tracks open streams across all tenants on the replica. The status
  latency timer runs from the file status transition (`occurredAt`) to the successful SSE write, so
  it includes any in-process fan-out delay but excludes client render time.
