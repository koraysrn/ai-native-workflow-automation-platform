# Observability Definition of Done

Verification steps for the observability spine: a real application metric must be emitted to
Prometheus and visible in Grafana, and a real trace must flow through the OpenTelemetry collector
with its trace ID visible in structured JSON logs.

## Metric

- `TaskService` increments the `awap.task.decisions` counter (label `decision`) on approve/reject.
- `GET /actuator/prometheus` exposes the metric (protected by the actuator security rules).
- Prometheus scrapes `backend:8080/actuator/prometheus` via `infra/prometheus/prometheus.yml`.
- Grafana provisions the `AWAP Overview` dashboard from
  `infra/grafana/provisioning/dashboards/awap.json`.

## Trace

- The backend exports spans to the OTel collector configured in
  `infra/otel-collector/otel-collector-config.yml`.
- Micrometer tracing propagates the trace ID into structured JSON logs
  (`backend/identity/src/main/resources/logback-spring.xml`).

## Verification checklist

The live proof is automated in
[`infra/observability/prove-observability.mjs`](../infra/observability/prove-observability.mjs)
and runs in the `Observability Proof` CI job on every pull request. Evidence is uploaded as the
`observability-evidence` artifact.

- [x] A task approve/reject increases `awap_task_decisions_total` in `/actuator/prometheus`.
- [x] Prometheus targets report the backend as UP.
- [x] Grafana renders the dashboard and the query returns data.
- [x] A request produces a span in the OTel collector (span count increases).
- [x] The JSON log line for that request carries the same trace ID as the span.

Each check fails with the missing metric, target, dashboard or trace ID; a collector outage fails
the job instead of being skipped. Local run:

```bash
make up
node infra/observability/prove-observability.mjs
```
