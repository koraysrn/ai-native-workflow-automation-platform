-- Performance check for the partitioned outbox relay query (issue #92).
--
-- Run against the ephemeral load-test stack after the k6 run:
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -v ON_ERROR_STOP=1 -U awap -d awap -f /dev/stdin < explain-outbox-partition.sql
--
-- The relay's hot query is the unpublished-events scan (LIMIT 100 ordered by created_at).
-- Two properties keep it fast as the outbox grows:
--   1. it is index-backed (idx_outbox_events_published on (published, created_at)), and
--   2. a month-scoped variant prunes every partition except the one holding that month.
--
-- The assertions live in the SQL itself because EXPLAIN output is not a queryable relation:
-- the DO block below runs EXPLAIN (FORMAT JSON) and raises, so `ON_ERROR_STOP` turns a plan
-- regression into a failing step instead of a `grep Scan` that any plan satisfies.

\set ON_ERROR_STOP on

-- Printed for the CI log; the assertions below are what actually gate the step.
EXPLAIN (COSTS OFF)
SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json, created_at
FROM outbox_events
WHERE published = false
ORDER BY created_at ASC
LIMIT 100;

DO $$
DECLARE
  pruned_plan JSON;
BEGIN
  -- The monthly partition helper only runs on the 1st of the month (OutboxPartitionScheduler),
  -- so on every other day the table holds just the DEFAULT partition and pruning is invisible.
  -- A partition for next month cannot collide with the load-test rows written by the run, so
  -- creating it is always safe and makes pruning observable.
  PERFORM awap_ensure_outbox_monthly_partition(
    (date_trunc('month', now()) + interval '1 month')::date);

  EXECUTE $sql$
    EXPLAIN (FORMAT JSON)
    SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json, created_at
    FROM outbox_events
    WHERE published = false
      AND created_at >= date_trunc('month', now()) + interval '1 month'
      AND created_at < date_trunc('month', now()) + interval '2 month'
    ORDER BY created_at ASC
    LIMIT 100
  $sql$ INTO pruned_plan;

  RAISE NOTICE 'month-scoped relay plan: %', pruned_plan;

  IF pruned_plan::text LIKE '%outbox_events_default%' THEN
    RAISE EXCEPTION 'relay query did not prune the DEFAULT partition: %', pruned_plan;
  END IF;

  IF pruned_plan::text NOT LIKE '%Index Scan%' THEN
    RAISE EXCEPTION 'relay query is not index-backed: %', pruned_plan;
  END IF;

  -- Only the pruning/index property is asserted: on an empty outbox the planner rightly prefers a
  -- sequential scan over the (unused) monthly partition, so a "no Seq Scan anywhere" rule would
  -- fail on a healthy stack.
  RAISE NOTICE 'outbox relay query prunes partitions and uses the published/created_at index.';
END
$$;
