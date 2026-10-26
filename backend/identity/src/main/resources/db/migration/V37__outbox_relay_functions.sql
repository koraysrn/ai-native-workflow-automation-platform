-- V37: SECURITY DEFINER relay functions.
--
-- The outbox relay is a system task: it must see every tenant's unpublished rows, while the
-- runtime role (awap_app) is constrained by row-level security and has no tenant context. Claiming
-- the batch atomically inside the function also prevents two relay replicas from publishing the
-- same event. The search_path is pinned to close the pg_temp shadow-table attack surface.

CREATE OR REPLACE FUNCTION awap_claim_outbox_batch(p_limit INT)
RETURNS TABLE(id UUID, event_type VARCHAR, aggregate_id UUID, payload_json TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  RETURN QUERY
  WITH claimed AS (
    SELECT o.id
    FROM outbox_events o
    WHERE o.published = false
    ORDER BY o.created_at ASC
    LIMIT GREATEST(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  )
  UPDATE outbox_events o
  SET published = true
  FROM claimed c
  WHERE o.id = c.id
  RETURNING o.id, o.event_type, o.aggregate_id, o.payload_json;
END
$$;

CREATE OR REPLACE FUNCTION awap_release_outbox_event(p_id UUID)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  UPDATE outbox_events SET published = false WHERE id = p_id;
  RETURN 1;
END
$$;
