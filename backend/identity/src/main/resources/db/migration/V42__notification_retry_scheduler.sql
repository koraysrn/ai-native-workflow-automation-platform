-- V42: Notification retry scheduler support (issue #119).
--
-- Retries no longer sleep inside the Kafka consumer transaction. The delivery row carries the
-- retry schedule plus a lease, and the scheduler claims due rows across tenants through the
-- SECURITY DEFINER helpers below (the runtime role awap_app is constrained by RLS and has no
-- tenant context). The search_path is pinned to close the pg_temp shadow-table attack surface.

ALTER TABLE notification_deliveries
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN lease_token UUID,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    -- Locale and variables are persisted so a retry renders exactly the same template.
    ADD COLUMN locale VARCHAR(20) NOT NULL DEFAULT 'en',
    ADD COLUMN variables_json TEXT,
    -- V46 (notification read state) also adds this column; keep it idempotent.
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Rows created before this migration have no schedule; make them immediately eligible so the
-- scheduler (and not the consumer) finishes the in-flight deliveries.
UPDATE notification_deliveries
SET next_attempt_at = updated_at
WHERE status IN ('PENDING', 'RETRYING')
  AND next_attempt_at IS NULL;

CREATE INDEX idx_notification_deliveries_due
    ON notification_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_notification_deliveries_tenant_due
    ON notification_deliveries (tenant_id, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_notification_deliveries_lease_until
    ON notification_deliveries (lease_until)
    WHERE lease_until IS NOT NULL;

CREATE INDEX idx_notification_deliveries_status_updated
    ON notification_deliveries (status, updated_at);

-- Claims due retries atomically: FOR UPDATE SKIP LOCKED keeps concurrent replicas apart, the
-- window function caps how many rows a single tenant may take in one batch (fairness), and the
-- lease token makes a takeover detectable after a crash.
CREATE OR REPLACE FUNCTION awap_claim_notification_retries(
    p_now TIMESTAMPTZ,
    p_lease_token UUID,
    p_lease_seconds INTEGER,
    p_limit INTEGER,
    p_per_tenant_limit INTEGER)
RETURNS TABLE(
    id UUID,
    tenant_id UUID,
    channel VARCHAR,
    recipient VARCHAR,
    template_key VARCHAR,
    locale VARCHAR,
    variables_json TEXT,
    attempts INTEGER,
    status VARCHAR,
    dedupe_key VARCHAR,
    next_attempt_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  RETURN QUERY
  WITH locked AS (
    SELECT d.id, d.tenant_id, d.next_attempt_at, d.created_at
    FROM notification_deliveries d
    WHERE d.status IN ('PENDING', 'RETRYING')
      AND d.next_attempt_at IS NOT NULL
      AND d.next_attempt_at <= p_now
      AND (d.lease_until IS NULL OR d.lease_until < p_now)
    ORDER BY d.next_attempt_at ASC, d.created_at ASC
    LIMIT GREATEST(p_limit, 1) * GREATEST(p_per_tenant_limit, 1)
    FOR UPDATE SKIP LOCKED
  ),
  ranked AS (
    SELECT l.id,
           row_number() OVER (
             PARTITION BY l.tenant_id
             ORDER BY l.next_attempt_at ASC, l.created_at ASC) AS rn
    FROM locked l
  ),
  picked AS (
    SELECT r.id
    FROM ranked r
    WHERE r.rn <= GREATEST(p_per_tenant_limit, 1)
    LIMIT GREATEST(p_limit, 1)
  )
  UPDATE notification_deliveries d
  SET lease_token = p_lease_token,
      lease_until = p_now + make_interval(secs => GREATEST(p_lease_seconds, 1)),
      updated_at = p_now
  FROM picked p
  WHERE d.id = p.id
  RETURNING d.id, d.tenant_id, d.channel, d.recipient, d.template_key, d.locale,
            d.variables_json, d.attempts, d.status, d.dedupe_key, d.next_attempt_at,
            d.last_attempt_at;
END
$$;

-- Retention: delivered and permanently failed rows are kept for the audit window (30 days by
-- default), retry rows that never converge are dropped after the shorter retry window (7 days).
CREATE OR REPLACE FUNCTION awap_purge_notification_deliveries(
    p_terminal_cutoff TIMESTAMPTZ,
    p_retry_cutoff TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_terminal INTEGER;
  v_retry INTEGER;
BEGIN
  DELETE FROM notification_deliveries
  WHERE status IN ('SENT', 'FAILED')
    AND updated_at < p_terminal_cutoff;
  GET DIAGNOSTICS v_terminal = ROW_COUNT;

  DELETE FROM notification_deliveries
  WHERE status IN ('PENDING', 'RETRYING')
    AND created_at < p_retry_cutoff;
  GET DIAGNOSTICS v_retry = ROW_COUNT;

  RETURN v_terminal + v_retry;
END
$$;

GRANT EXECUTE ON FUNCTION awap_claim_notification_retries(
    TIMESTAMPTZ, UUID, INTEGER, INTEGER, INTEGER) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_purge_notification_deliveries(TIMESTAMPTZ, TIMESTAMPTZ) TO awap_app;
