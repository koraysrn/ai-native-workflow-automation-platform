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
  benchmark: [`load-test/cdc-benchmark.mjs`](../load-test/cdc-benchmark.mjs);
  Compose collector: [`load-test/collect-outbox-lag.mjs`](../load-test/collect-outbox-lag.mjs).
- Measured polling-vs-CDC CI run (2026-11-02, 400 events per mode): polling p95 6569 ms vs CDC
  p95 257 ms, verdict `cutover: true` (publish-lag trigger):
  [`benchmarks/cdc-benchmark-2026-11-02.json`](benchmarks/cdc-benchmark-2026-11-02.json).
- Live end-to-end registration (2026-11-03): the connector was registered against a real
  Postgres + Kafka + Connect stack; a new outbox row streamed to `outbox.event.TASK`, the polling
  path consumed the same event, duplicate CDC payloads collapsed to one delivery, stopping the
  connector fired `AwapCdcSlotInactive`, and all gap rows streamed after resume. Results:
  [`benchmarks/cdc-e2e-2026-11-03.json`](benchmarks/cdc-e2e-2026-11-03.json); procedure:
  [`cdc-cutover-runbook.md`](cdc-cutover-runbook.md#live-end-to-end-verification-issue-161).
- Debezium prerequisites: `wal_level=logical` and `REPLICA IDENTITY FULL` on the partitioned
  outbox (V50); the Compose stack and Flyway migrations provide both.
- Alerts: [`infra/prometheus/cdc-alert-rules.yml`](../infra/prometheus/cdc-alert-rules.yml). The
  live run found and fixed a dead slot-lag expression (`pg_replication_slots_pg_wal_lsn_diff` is
  the series postgres_exporter actually emits).

Polling remains the default. The benchmark has now produced the first trigger-firing run; a
production cutover still requires the same measured signal on the production workload.
