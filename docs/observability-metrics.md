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
| `awap.workflow.expression.validations` | `awap_workflow_expression_validations_total` | `WorkflowExpressionValidationService` | – (designer inline feedback) | – |
| `awap.outbox.pending` | `awap_outbox_pending` | `OutboxRelayService` (gauge) | Outbox backlog, relay rate and DLQ | `AwapOutboxBacklogStuck` |
| `awap.outbox.events` | `awap_outbox_events_total` | `OutboxRelayService` | Outbox backlog, relay rate and DLQ | – |
| `awap.dlq.messages` | `awap_dlq_messages_total` | `DlqConsumer` | Outbox backlog, relay rate and DLQ | `AwapWebhookDeadGrowth` |
| HTTP observations | `http_server_requests_seconds_count` | Spring Boot | Outbox backlog, relay rate and DLQ (5xx) | `AwapHighErrorRate`, `AwapHighLatency` |

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
