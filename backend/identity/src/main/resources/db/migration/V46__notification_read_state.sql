-- V46: In-app read/unread state for notification deliveries (issue #126).
--
-- Design choice: read state is stored on notification_deliveries instead of a
-- separate notification_reads table. Every delivery row is addressed to exactly
-- one recipient (recipient + channel + dedupe_key is unique per tenant), so the
-- acknowledgement is single-user by construction; a join table would add a row
-- per read without enabling multi-user semantics that the delivery ledger can
-- express. This also keeps the unread predicate a single indexed column instead
-- of a NOT EXISTS subquery on every inbox listing.
--
-- Retention: read rows may age out much faster than unread ones, so the purge
-- helpers below filter on read_at and created_at independently. They are
-- SECURITY DEFINER (owned by the migration role) with a pinned search_path
-- because the scheduled purge runs without a tenant context and RLS would
-- otherwise hide every row.

ALTER TABLE notification_deliveries ADD COLUMN read_at TIMESTAMPTZ;
ALTER TABLE notification_deliveries ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_notification_deliveries_tenant_read
    ON notification_deliveries (tenant_id, read_at);

-- Partial index serving both the unread-only listing and the unread counter.
CREATE INDEX idx_notification_deliveries_tenant_unread
    ON notification_deliveries (tenant_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE FUNCTION awap_purge_read_notifications(p_cutoff TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  removed INTEGER;
BEGIN
  DELETE FROM notification_deliveries
   WHERE read_at IS NOT NULL
     AND read_at < p_cutoff;
  GET DIAGNOSTICS removed = ROW_COUNT;
  RETURN removed;
END;
$$;

CREATE FUNCTION awap_purge_unread_notifications(p_cutoff TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  removed INTEGER;
BEGIN
  DELETE FROM notification_deliveries
   WHERE read_at IS NULL
     AND created_at < p_cutoff;
  GET DIAGNOSTICS removed = ROW_COUNT;
  RETURN removed;
END;
$$;

GRANT EXECUTE ON FUNCTION awap_purge_read_notifications(TIMESTAMPTZ) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_purge_unread_notifications(TIMESTAMPTZ) TO awap_app;
