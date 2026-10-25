-- Performance check for the partitioned outbox relay query (issue #92).
--
-- Run against the ephemeral load-test stack after the k6 run:
--   docker compose exec postgres psql -U awap -d awap -f /dev/stdin < explain-outbox-partition.sql
--
-- The relay's hot query is the unpublished-events scan (LIMIT 100 ordered by created_at)
-- plus the atomic claim UPDATE. Both must use the partition key/index rather than a
-- sequential scan across all monthly partitions.
EXPLAIN ANALYZE
SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at
FROM outbox_events
WHERE published = false
ORDER BY created_at ASC
LIMIT 100;

EXPLAIN ANALYZE
UPDATE outbox_events
SET published = true, published_at = now()
WHERE id = '00000000-0000-0000-0000-000000000000'
  AND published = false;
