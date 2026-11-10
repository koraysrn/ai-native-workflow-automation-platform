-- V51 (#191): carry the request correlation ID through the outbox to Kafka.
--
-- The ID is captured when the event is written (same transaction as the business change) and the
-- relay copies it into the record header, so a consumer can restore it into its MDC. The claim
-- function's return type changes, which requires dropping and recreating it.

ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(64);

DROP FUNCTION IF EXISTS awap_claim_outbox_batch(INT, INT, INT);

CREATE FUNCTION awap_claim_outbox_batch(
    p_limit INT,
    p_lease_seconds INT,
    p_max_attempts INT)
RETURNS TABLE(
    id UUID,
    event_type VARCHAR,
    aggregate_id UUID,
    payload_json TEXT,
    claim_token UUID,
    correlation_id VARCHAR)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_lease_seconds INT := GREATEST(COALESCE(p_lease_seconds, 60), 1);
  v_max_attempts INT := GREATEST(COALESCE(p_max_attempts, 5), 1);
BEGIN
  RETURN QUERY
  WITH claimed AS (
    SELECT o.id
    FROM outbox_events o
    WHERE o.published = false
      AND o.publish_attempts < v_max_attempts
      AND (o.claimed_at IS NULL
           OR o.claimed_at <= now() - make_interval(secs => v_lease_seconds))
    ORDER BY o.created_at ASC
    LIMIT GREATEST(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  )
  UPDATE outbox_events o
  SET claimed_at = now(),
      claim_token = gen_random_uuid()
  FROM claimed c
  WHERE o.id = c.id
  RETURNING o.id,
            o.event_type,
            o.aggregate_id,
            o.payload_json,
            o.claim_token,
            o.correlation_id;
END
$$;

-- The drops above remove the V44 grants; restore the least-privilege contract.
REVOKE ALL ON FUNCTION awap_claim_outbox_batch(INT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_claim_outbox_batch(INT, INT, INT) TO awap_app;
