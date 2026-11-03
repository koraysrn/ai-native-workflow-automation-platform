# Outbox CDC cutover runbook (Debezium)

Companion to [`outbox-cdc-evaluation.md`](outbox-cdc-evaluation.md) and
[ADR-0004](adr/0004-outbox-cdc-cutover.md). The polling relay stays the default; CDC is enabled
only when the measured triggers below fire.

## Triggers (measured, not predicted)

| Signal | Threshold | Source |
|---|---|---|
| Outbox publish lag p95 | > 5s | `awap_outbox_pending` + relay logs / benchmark |
| Consecutive sweeps with a leftover backlog | ≥ 3 | relay logs |
| Replication slot lag | > 512 MiB | postgres_exporter |
| WAL growth | > 128 MiB/min | postgres_exporter |

`load-test/lib/cdc-triggers.mjs` implements the same numbers; `evaluateCutover()` is what the
benchmark prints, so operations and CI share one definition.

## Measured benchmark (issue #159)

The polling-vs-CDC comparison is collected from this Compose stack by
[`collect-outbox-lag.mjs`](../load-test/collect-outbox-lag.mjs), which reads
`published_at - created_at` from Postgres for polling and joins Debezium's Kafka records to the
same outbox rows for CDC. [`cdc-benchmark.mjs`](../load-test/cdc-benchmark.mjs) prints the
p50/p95/max for both modes plus the `evaluateCutover()` verdict.

The [CI benchmark run](https://github.com/koraysrn/ai-native-workflow-automation-platform/actions/runs/41504784486)
(200 workflow starts -> 400 outbox events, relay interval 1s, connector `RUNNING`) is committed as
[`benchmarks/cdc-benchmark-2026-11-02.json`](benchmarks/cdc-benchmark-2026-11-02.json):

| Mode | Samples | p50 | p95 | max |
|---|---|---|---|---|
| Polling relay | 400 | 4977 ms | 6569 ms | 6786 ms |
| Debezium CDC | 400 | 140 ms | 257 ms | 320 ms |

`evaluateCutover()` verdict: **cutover**. Polling p95 (6569 ms) exceeds the 5000 ms trigger, so the
measured workload is the first evidence that justifies enabling CDC. The collector measured publish
lag only; the backlog-sweep, slot-lag and WAL-growth inputs defaulted to zero. CDC lowered p95 by
6312 ms while the connector was `RUNNING`.

The `cdc-benchmark-results` artifact from that run contains `polling.json`, `cdc.json`,
`cdc-benchmark.json`, the connector status and the stack logs. Every run of the
[Outbox CDC benchmark workflow](../.github/workflows/cdc-benchmark.yml) uploads the same artifact
(30-day retention) and fails if the connector is registered but produces no samples.

## Live end-to-end verification (issue #161)

The connector is now registered against a real PostgreSQL + Kafka + Debezium Connect stack, not
only validated statically. [`cdc-e2e.mjs`](../load-test/cdc-e2e.mjs) drives the full acceptance
list and writes a reviewable JSON artifact:

```bash
# 1. Main stack with Kafka and a 1s relay, plus the Connect worker
KAFKA_ENABLED=true OUTBOX_RELAY_INTERVAL_MS=1000 \
  docker compose -f infra/docker-compose.yml up -d --build \
  postgres redis kafka schema-registry backend postgres-exporter prometheus
docker compose -f infra/docker-compose.cdc.yml up -d

# 2. Seed a tenant and run every live assertion
node load-test/seed.mjs
node load-test/cdc-e2e.mjs --output load-test/results/cdc-e2e.json
```

The scheduled/manual [`cdc-e2e.yml`](../.github/workflows/cdc-e2e.yml) workflow runs the same
command and uploads the `cdc-e2e-results` artifact. The committed artifact
[`benchmarks/cdc-e2e-2026-11-03.json`](benchmarks/cdc-e2e-2026-11-03.json) is the upload from the
final CI proof, [run 41645994110](https://github.com/koraysrn/ai-native-workflow-automation-platform/actions/runs/41645994110)
(10/10 assertions passed):

| Acceptance item | Assertion | Measured result |
|---|---|---|
| Compose/K8s stack starts Connect and registers the connector | `compose-connector-registered`; K8s `awap-connect-register` Job | connector and task `RUNNING`; the Job uses the same ConfigMap/Secret, PUT/POST and status poll |
| A new outbox row streams to `outbox.event.<aggregate>` | `outbox-row-streamed-to-aggregate-topic` | event `cebcf54a…` on `outbox.event.TASK`, key `4b1b5642…`, `tenantId` header present |
| The same row is consumed during the dual run | `dual-run-polling-consumed` | same event id/key on `workflow.task.events` |
| Duplicates are absorbed by existing dedupe keys | `duplicate-events-absorbed` | the exact CDC payload replayed twice produced **one** `notification_deliveries` row (`business_key=cdc-e2e-41645994110-1`) |
| Slot lag / inactive-slot alerts | `connector-stop-makes-slot-inactive`, `inactive-slot-alert-fires`, `slot-lag-rule-loaded` | slot `active=false` with 15,176 bytes of retained WAL; `AwapCdcSlotInactive` reached `firing`; the 512 MiB rule is loaded and healthy |
| Resume without loss | `connector-resumed`, `resume-without-loss`, `inactive-slot-alert-resolves` | slot active again, all 3 rows written during the stop were streamed, alert returned to `inactive` |

The live run found a dead alert expression: `postgres_exporter` emits the plural series
`pg_replication_slots_pg_wal_lsn_diff`, while [`cdc-alert-rules.yml`](../infra/prometheus/cdc-alert-rules.yml)
used the singular `pg_replication_slot_pg_wal_lsn_diff`, so the 512 MiB alert could never fire.
The rule, the synthetic exporter, the metric catalog and the live-query validator now use the
plural name. [`cdc-alert-rules.test.yml`](../infra/prometheus/test/cdc-alert-rules.test.yml)
feeds both threshold and `for:` inputs through `promtool`, so the 512 MiB case is proved without
generating half a gigabyte of WAL on every run.

The duplicate suppression counter lives on the auth-protected `/actuator/prometheus` endpoint
(HTTP 401 in this stack), so the harness treats it as optional evidence and asserts the durable
`notification_deliveries` row count instead. The Kubernetes path applies
[`debezium-connect.yaml`](../infra/k8s/debezium-connect.yaml), whose `awap-connect-register` Job
registers the connector with the same connector JSON and Secret-backed DB credentials; the live CI
job uses Compose because that is the available stack.

## Prerequisites for CDC

- PostgreSQL must run with `wal_level=logical`. The Compose stack defaults to logical (override with
  `POSTGRES_WAL_LEVEL=replica` only when CDC will never be enabled).
- The partitioned `outbox_events` table must have a replica identity. Flyway migration V50 sets
  `REPLICA IDENTITY FULL` on the parent, existing partitions and future monthly partitions; without
  it the relay's claim `UPDATE` is rejected once the table is published.
- `max_replication_slots` and `max_wal_senders` must leave room for the `awap_outbox` slot.

## Deployment

```bash
# 1. Connect worker (Compose)
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.cdc.yml up -d

# 2. Register the outbox connector (reads DB_* from the environment)
DB_HOST=postgres DB_USER=awap DB_PASSWORD=awap DB_NAME=awap \
  ./infra/debezium/register-connector.sh

# Kubernetes (the manifest includes the connector ConfigMap and registration Job)
kubectl apply -f infra/k8s/debezium-connect.yaml
kubectl -n awap wait --for=condition=complete job/awap-connect-register --timeout=300s
kubectl -n awap logs job/awap-connect-register
kubectl -n awap port-forward svc/awap-connect 8083:8083
```

A completed Kubernetes Job is immutable. After changing the connector ConfigMap, recreate the
one-shot Job before applying:

```bash
kubectl -n awap delete job awap-connect-register --ignore-not-found
kubectl apply -f infra/k8s/debezium-connect.yaml
```

The Job installs `curl`, `jq` and `gettext` from the Alpine repositories. In an air-gapped
cluster, mirror those packages for the bootstrap Job or skip the Job and run
`register-connector.sh` through `kubectl -n awap port-forward svc/awap-connect 8083:8083`.

Connector topic routing (`EventRouter`, `route.by.field=aggregate_type`):

| `aggregate_type` | CDC topic (case-sensitive) | Polling topic |
|---|---|---|
| `TASK` | `outbox.event.TASK` | `workflow.task.events` |
| `FORM` | `outbox.event.FORM` | `workflow.instance.events` |
| `WORKFLOW` | `outbox.event.WORKFLOW` | `workflow.instance.events` |
| `NOTIFICATION` | `outbox.event.NOTIFICATION` | `notification.send` |
| `DOCUMENT` | `outbox.event.DOCUMENT` | `document.events` |
| `ANALYTICS` | `outbox.event.ANALYTICS` | `analytics.events` |

Consumers must therefore subscribe to both topic families during the dual-run window.

## Dual-run (minimum 48h)

1. Register the connector; `snapshot.mode=no_data` means only new rows stream — historical rows
   were already published by polling.
2. Keep `OutboxRelayService` running. Both paths publish the same event id, so consumers deduplicate
   by event id (notifications already dedupe on `tenant_id + dedupe_key`, tasks on the task id).
3. Compare `awap_outbox_pending` with the connector's slot lag and with the consumer offsets; the
   duplication rate must stay below 5% (`CDC_TRIGGERS.duplicateRate`).
4. Do not purge outbox rows while the connector offsets are behind the retention window.

## Cutover

1. Confirm the triggers and a healthy dual run: connector `RUNNING`, slot active, duplicate rate
   below 5%, no consumer lag growth.
2. Scale `OutboxRelayService` out of the publishing path (feature flag/`awap.outbox.relay.enabled=false`
   in the deployment) so only CDC writes to Kafka. Keep the relay code deployed for rollback.
3. Watch `outbox.event.*` consumer lag and `awap_outbox_pending` for 30 minutes.
4. Run the reconciliation query; it must return zero missing events.

## Rollback to polling

1. Re-enable the relay (`awap.outbox.relay.enabled=true`) first; it will publish every unpublished row.
2. Stop the connector task (`PUT /connectors/awap-outbox-connector/stop`) but keep the slot for
   investigation, or pause the Connect deployment.
3. Reconcile: rows created during the gap are picked up by the relay; duplicated events are absorbed
   by consumer dedupe keys.
4. When `awap_outbox_pending` returns to its baseline, drop the slot
   (`SELECT pg_drop_replication_slot('awap_outbox');`) to stop WAL retention.

## Reconciliation

```sql
-- Unpublished rows older than 5 minutes (relay/CDC gap detector).
SELECT count(*) AS unpublished_rows
FROM outbox_events
WHERE published = false AND created_at < now() - interval '5 minutes';

-- Per-tenant duplicate risk during the dual run.
SELECT tenant_id, count(*)
FROM notification_deliveries
WHERE created_at > now() - interval '1 hour'
GROUP BY tenant_id
HAVING count(*) > 1000;
```

## Monitoring

- `AwapCdcSlotInactive`, `AwapCdcSlotLagHigh`, `AwapCdcRollbackRequired` live in
  [`infra/prometheus/cdc-alert-rules.yml`](../infra/prometheus/cdc-alert-rules.yml) and require
  postgres_exporter.
- Connector status: `GET :8083/connectors/awap-outbox-connector/status` (RUNNING/FAILED + task trace).
- Dead letters land on `awap.cdc.dead-letter` with context headers (`errors.deadletterqueue.*`).
- Dashboard: `AWAP platform overview` → "Outbox backlog, relay rate and DLQ".

## Operational cost

Kafka Connect adds a deployment (1 replica, 0.25–1 CPU), connector/offset/status topics, a
replication slot and a second consumer path during dual-run. This is why polling remains the
default until the triggers above are met; the cost/benefit decision is recorded in ADR-0004.
