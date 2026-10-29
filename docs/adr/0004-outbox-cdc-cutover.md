# ADR-0004 — Outbox publication: polling by default, Debezium CDC on measured triggers

- **Status:** Accepted
- **Date:** 2026-10-27
- **Context:** Issue #131, evaluation in [`outbox-cdc-evaluation.md`](../outbox-cdc-evaluation.md)

## Context

`OutboxRelayService` polls `outbox_events` every 5 seconds with an atomic claim and a bounded batch.
This is simple and has no additional infrastructure. Capacity is bounded by the sweep interval and
the batch size; a sustained write burst can therefore exceed the relay's drain rate.

## Decision

1. **Keep polling as the default publishing path.**
2. Deploy the Debezium connector configuration (Compose + Kubernetes) so a cutover is a
   registration + flag change, not a re-architecture.
3. Cut over only when a *measured* trigger fires: publish lag p95 > 5s, ≥ 3 consecutive sweeps with
   a leftover backlog, slot lag > 512 MiB or WAL growth > 128 MiB/min.
4. During the dual-run (≥ 48h) both paths publish; consumers deduplicate by event id
   (`tenant_id + dedupe_key` for notifications, task id for tasks).
5. Roll back to polling whenever the connector is inactive for 15 minutes or the duplicate rate
   exceeds 5%.

## Consequences

- Positive: no change to the happy path; the cutover path is prepared and tested; the polling relay
  remains a proven rollback.
- Negative: Kafka Connect, three internal topics and a replication slot must be operated; WAL grows
  if the connector stalls, hence the slot-lag alerts.
- Neutral: the connector routes by `aggregate_type` (`outbox.event.<type>`), so consumers subscribe
  to both topic families during the dual run.

## Compliance

- Triggers are implemented once in [`load-test/lib/cdc-triggers.mjs`](../load-test/lib/cdc-triggers.mjs)
  and used by the benchmark and tests.
- The procedure, reconciliation queries and rollback are in
  [`cdc-cutover-runbook.md`](../cdc-cutover-runbook.md).
- Alert rules live in [`infra/prometheus/cdc-alert-rules.yml`](../infra/prometheus/cdc-alert-rules.yml).
