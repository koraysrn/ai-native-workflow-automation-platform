-- V44 (#121): lease-based outbox claiming + reconciliation helpers.
--
-- V37 claimed a batch by flipping `published = true` before the Kafka send. A crash between the
-- claim and the publish therefore lost the event forever: the row looked published and no relay or
-- scheduler ever looked at it again. This migration introduces an explicit lease:
--
--   * `claimed_at` / `claim_token` are the lease coordinates (a claim never implies publication),
--   * `published_at` records when the event actually reached Kafka,
--   * `publish_attempts` counts failed sends so a poison payload cannot loop forever.
--
-- Legacy rows (published = true, published_at IS NULL) are deliberately NOT backfilled: they are
-- the crash-window evidence operators must verify manually before republishing. See the
-- "O7 - Outbox claim reconciliation" section of docs/operations-runbook.md.

ALTER TABLE outbox_events ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN claim_token UUID;
ALTER TABLE outbox_events ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN publish_attempts INTEGER NOT NULL DEFAULT 0;

-- Reconciliation query: WHERE published = false AND claimed_at <= now() - lease.
CREATE INDEX idx_outbox_events_reconciliation ON outbox_events (published, claimed_at);

-- Legacy/partial-state report: WHERE published = true AND published_at IS NULL.
CREATE INDEX idx_outbox_events_legacy_published ON outbox_events (published, published_at);

-- The V37 functions encoded the crash-window bug (claim == publish) and must not survive.
DROP FUNCTION IF EXISTS awap_claim_outbox_batch(INT);
DROP FUNCTION IF EXISTS awap_release_outbox_event(UUID);

-- Claims a batch with a lease while leaving `published = false`. Only rows whose claim is missing
-- or older than the lease may be claimed again, and rows that exhausted their attempts are skipped
-- so a corrupted payload cannot be retried forever.
CREATE FUNCTION awap_claim_outbox_batch(
    p_limit INT,
    p_lease_seconds INT,
    p_max_attempts INT)
RETURNS TABLE(
    id UUID,
    event_type VARCHAR,
    aggregate_id UUID,
    payload_json TEXT,
    claim_token UUID)
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
  RETURNING o.id, o.event_type, o.aggregate_id, o.payload_json, o.claim_token;
END
$$;

-- Marks an event as actually published. The claim token is the fencing token: a relay whose lease
-- expired (and was reclaimed by another relay) can no longer mark the row, and a row can only be
-- marked once because `published = false` is part of the predicate.
CREATE FUNCTION awap_mark_outbox_published(p_id UUID, p_claim_token UUID)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_rows integer := 0;
BEGIN
  UPDATE outbox_events
  SET published = true,
      published_at = now(),
      claimed_at = NULL,
      claim_token = NULL
  WHERE id = p_id
    AND published = false
    AND claim_token = p_claim_token;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  RETURN v_rows;
END
$$;

-- Releases a claim after a failed publish: the row becomes claimable again and the attempt counter
-- is incremented so repeated failures eventually stop the retry loop.
CREATE FUNCTION awap_release_outbox_event(p_id UUID, p_claim_token UUID)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_rows integer := 0;
BEGIN
  UPDATE outbox_events
  SET claimed_at = NULL,
      claim_token = NULL,
      publish_attempts = publish_attempts + 1
  WHERE id = p_id
    AND published = false
    AND claim_token = p_claim_token;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  RETURN v_rows;
END
$$;

-- Re-queues rows whose claiming relay died inside the crash window. The lease comparison happens on
-- the database clock, so a relay with a skewed local clock can neither release a fresh claim nor
-- keep a stale one alive.
CREATE FUNCTION awap_release_stale_outbox_claims(p_lease_seconds INT, p_limit INT)
RETURNS TABLE(id UUID, tenant_id UUID, claim_age_seconds DOUBLE PRECISION)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_lease_seconds INT := GREATEST(COALESCE(p_lease_seconds, 60), 1);
BEGIN
  RETURN QUERY
  WITH stale AS (
    SELECT o.id, o.claimed_at
    FROM outbox_events o
    WHERE o.published = false
      AND o.claim_token IS NOT NULL
      AND o.claimed_at <= now() - make_interval(secs => v_lease_seconds)
    ORDER BY o.claimed_at ASC
    LIMIT GREATEST(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  )
  UPDATE outbox_events o
  SET claimed_at = NULL,
      claim_token = NULL,
      publish_attempts = publish_attempts + 1
  FROM stale s
  WHERE o.id = s.id
  RETURNING o.id, o.tenant_id, EXTRACT(EPOCH FROM (now() - s.claimed_at))::DOUBLE PRECISION;
END
$$;

-- Legacy/partial state: the V37 relay flipped `published` before publishing, so a crash left rows
-- that look published but were never sent. They are reported to operators, never auto-republished
-- (a blind replay could duplicate a business transaction that did reach Kafka).
CREATE FUNCTION awap_find_legacy_published_outbox(p_limit INT)
RETURNS TABLE(
    id UUID,
    tenant_id UUID,
    event_type VARCHAR,
    created_at TIMESTAMPTZ,
    publish_attempts INTEGER)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT o.id, o.tenant_id, o.event_type, o.created_at, o.publish_attempts
  FROM outbox_events o
  WHERE o.published = true
    AND o.published_at IS NULL
  ORDER BY o.created_at ASC
  LIMIT GREATEST(p_limit, 1)
$$;

-- Number of rows that exhausted their publish attempts (quarantined, needs operator action).
CREATE FUNCTION awap_count_exhausted_outbox(p_max_attempts INT)
RETURNS bigint
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT count(*)
  FROM outbox_events o
  WHERE o.published = false
    AND o.publish_attempts >= GREATEST(COALESCE(p_max_attempts, 5), 1)
$$;

-- Least privilege: the relay role is the only runtime caller of these system-wide helpers.
REVOKE ALL ON FUNCTION awap_claim_outbox_batch(INT, INT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION awap_mark_outbox_published(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION awap_release_outbox_event(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION awap_release_stale_outbox_claims(INT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION awap_find_legacy_published_outbox(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION awap_count_exhausted_outbox(INT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION awap_claim_outbox_batch(INT, INT, INT) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_mark_outbox_published(UUID, UUID) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_release_outbox_event(UUID, UUID) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_release_stale_outbox_claims(INT, INT) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_legacy_published_outbox(INT) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_count_exhausted_outbox(INT) TO awap_app;
