/**
 * Synthetic Prometheus exposition used by `validate-live-queries.mjs` (issue #160).
 *
 * The real backend only emits a metric after the matching business event runs, so a live
 * query check against a freshly started stack would see empty panels. This exporter emits one
 * representative series for every metric the dashboard and the alert rules reference, with
 * counters that increase on every scrape so `rate()`/`increase()` produce a value.
 *
 * The `/metrics` path mirrors `backend:8080/actuator/prometheus`; `/postgres-metrics` mirrors
 * the `postgres_exporter` series used by `cdc-alert-rules.yml`.
 */

const BACKEND_COUNTERS = [
  // Panel "Workflow success vs failure rate" and "Workflow failure ratio".
  ["awap_workflow_success_total", { tenant: "acme" }, 1],
  ["awap_workflow_success_total", { tenant: "globex" }, 0.5],
  ["awap_workflow_failure_total", { tenant: "acme" }, 0.2],
  ["awap_workflow_failure_total", { tenant: "globex" }, 0.05],
  // Panel "Task completion rate".
  ["awap_task_completed_total", { tenant: "acme" }, 1],
  // Panel "Task decisions".
  ["awap_task_decisions_total", { decision: "approved" }, 1],
  ["awap_task_decisions_total", { decision: "rejected" }, 0.25],
  // Panel "SLA breaches (1h)".
  ["awap_sla_breaches_total", { tenant: "acme" }, 0.01],
  // Panel "Notification deliveries and failures".
  ["awap_notification_deliveries_total", { channel: "email", result: "success" }, 1],
  ["awap_notification_deliveries_total", { channel: "webhook", result: "failure" }, 0.1],
  ["awap_notification_attempts_total", { channel: "email", result: "success" }, 1],
  ["awap_notification_attempts_total", { channel: "webhook", result: "failed" }, 0.2],
  // Panel "Outbox backlog, relay rate and DLQ".
  ["awap_outbox_events_total", { result: "published" }, 1],
  ["awap_dlq_messages_total", { topic: "awap.dlq" }, 0.01],
  ["http_server_requests_seconds_count", { status: "200", uri: "/api" }, 5],
  ["http_server_requests_seconds_count", { status: "500", uri: "/api" }, 0.1],
];

const BACKEND_GAUGES = [
  // Panel "Outbox backlog, relay rate and DLQ".
  ["awap_outbox_pending", {}, 42],
];

const BACKEND_HISTOGRAMS = [
  // Panel "Workflow process duration p95".
  ["awap_process_duration_seconds", { tenant: "acme" }, [0.1, 0.5, 1, 5]],
  ["awap_process_duration_seconds", { tenant: "globex" }, [0.1, 0.5, 1, 5]],
  // Panel "Notification retry lag and dispatch latency".
  ["awap_notification_retry_lag_seconds", { channel: "email" }, [0.1, 0.5, 1, 5]],
  ["awap_notification_retry_lag_seconds", { channel: "webhook" }, [0.1, 0.5, 1, 5]],
  ["awap_notification_dispatch_latency_seconds", { channel: "email" }, [0.1, 0.5, 1, 5]],
  ["awap_notification_dispatch_latency_seconds", { channel: "webhook" }, [0.1, 0.5, 1, 5]],
];

// Series used by `infra/prometheus/cdc-alert-rules.yml`.
const CDC_EXPORTER_GAUGES = [
  ["pg_replication_slots_active", { slot_name: "awap_outbox" }, 1],
  ["pg_replication_slots_pg_wal_lsn_diff", { slot_name: "awap_outbox" }, 0],
];

function escapeLabel(value) {
  return String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("\n", "\\n");
}

function formatLabels(labels) {
  const entries = Object.entries(labels);
  if (entries.length === 0) {
    return "";
  }
  return `{${entries.map(([key, value]) => `${key}="${escapeLabel(value)}"`).join(",")}}`;
}

/** Formats one exposition line; `undefined` labels render as a bare metric name. */
export function sample(name, labels, value) {
  return `${name}${formatLabels(labels)} ${value}`;
}

function counterSamples(tick) {
  return BACKEND_COUNTERS.map(([name, labels, perTick]) => sample(name, labels, perTick * tick));
}

function gaugeSamples(tick) {
  // Gauges stay constant; `tick` is accepted so both renderers share a signature.
  return BACKEND_GAUGES.map(([name, labels, value]) => sample(name, labels, value));
}

function histogramSamples(tick) {
  const lines = [];
  for (const [name, labels, bounds] of BACKEND_HISTOGRAMS) {
    // Put every observation in the smallest bucket: the counts stay non-decreasing and
    // `histogram_quantile` returns a value instead of the +Inf fallback.
    for (const bound of [...bounds, "+Inf"]) {
      lines.push(sample(`${name}_bucket`, { ...labels, le: bound }, tick));
    }
    lines.push(sample(`${name}_count`, labels, tick));
    lines.push(sample(`${name}_sum`, labels, tick * 0.05));
  }
  return lines;
}

/** Returns the `/metrics` body (backend/actuator exposition). */
export function renderBackendMetrics(tick) {
  return `${[...counterSamples(tick), ...gaugeSamples(tick), ...histogramSamples(tick)].join("\n")}\n`;
}

/** Returns the `/postgres-metrics` body (postgres_exporter exposition). */
export function renderExporterMetrics(tick) {
  return `${CDC_EXPORTER_GAUGES.map(([name, labels, value]) => sample(name, labels, value)).join("\n")}\n`;
}
