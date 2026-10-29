# Outbox Polling vs CDC (Debezium) — Evaluation

## Current state

The outbox is polled by [`OutboxRelayService`](../backend/identity/src/main/java/com/awap/engine/application/OutboxRelayService.java)
every 5 seconds, claiming events atomically and publishing to Kafka.

## Decision

**Keep polling for the current and near-term volume.** Reasons:

- Polling with an indexed `(published, created_at)` and a bounded batch size is
  simple and has no extra infrastructure.
- CDC (Debezium) adds a Kafka Connect cluster, connector management and
  operational burden that is not justified yet.

## Trigger for CDC

Switch to CDC when **any** of these is measured, not predicted:

- Outbox publish lag (event creation → Kafka publish) exceeds 5s at p95.
- The relay sweep reads a full batch and still has a backlog after multiple
  consecutive sweeps.
- DB replication-slot / WAL load becomes acceptable and throughput demand
  exceeds polling capacity.

## Required improvements either way

- Partition `outbox_events` by `created_at` (see `V27__outbox_partitioning.sql`).
- Batch reads with a bounded size.
- Purge published records on a schedule.

## Implementation status (issue #131)

The cutover is now implemented behind measured triggers:

- Connector configuration: [`infra/debezium/outbox-connector.json`](../infra/debezium/outbox-connector.json)
  (Postgres connector, `outbox_events` only, EventRouter routing by `aggregate_type`,
  dead-letter topic `awap.cdc.dead-letter`).
- Deployments: [`infra/docker-compose.cdc.yml`](../infra/docker-compose.cdc.yml) and
  [`infra/k8s/debezium-connect.yaml`](../infra/k8s/debezium-connect.yaml);
  registration via [`infra/debezium/register-connector.sh`](../infra/debezium/register-connector.sh).
- Triggers, dual-run, cutover, rollback and reconciliation:
  [`cdc-cutover-runbook.md`](cdc-cutover-runbook.md) and
  [`adr/0004-outbox-cdc-cutover.md`](adr/0004-outbox-cdc-cutover.md).
- Trigger logic (shared by docs, benchmark and tests):
  [`load-test/lib/cdc-triggers.mjs`](../load-test/lib/cdc-triggers.mjs);
  benchmark: [`load-test/cdc-benchmark.mjs`](../load-test/cdc-benchmark.mjs).
- Alerts: [`infra/prometheus/cdc-alert-rules.yml`](../infra/prometheus/cdc-alert-rules.yml).

Polling remains the default until one of the triggers above fires in a measured run.
