-- V43: TTL and business-key policy for notification de-duplication (issue #120).
--
-- The old key (tenant/channel/recipient/template/variables hash) suppressed legitimate repeats for
-- the whole 30-day retention window. A delivery now carries an explicit `dedupe_expires_at`; the
-- (tenant_id, dedupe_key) unique constraint only rejects a duplicate while that window is active.
--
-- PostgreSQL partial indexes cannot depend on `now()`, so the active-window rule is enforced by a
-- BEFORE INSERT trigger: expired terminal rows are renamed to an archival key (which keeps the row
-- for audit) and the new delivery is admitted. Concurrent inserts still serialise on the unique
-- constraint, so exactly one delivery wins. A business key is stored for audit/traceability and the
-- tenant must exist and not be soft-deleted.

ALTER TABLE notification_deliveries
    ADD COLUMN dedupe_expires_at TIMESTAMPTZ,
    ADD COLUMN business_key TEXT;

-- Existing rows keep their historical semantics: they stay deduplicated for the default window
-- measured from creation instead of for the full 30-day retention period.
UPDATE notification_deliveries
SET dedupe_expires_at = created_at + INTERVAL '1 hour'
WHERE dedupe_expires_at IS NULL;

ALTER TABLE notification_deliveries
    ALTER COLUMN dedupe_expires_at SET NOT NULL;

CREATE INDEX idx_notification_deliveries_dedupe_expires
    ON notification_deliveries (dedupe_expires_at);

CREATE INDEX idx_notification_deliveries_tenant_expires
    ON notification_deliveries (tenant_id, dedupe_expires_at);

CREATE OR REPLACE FUNCTION awap_notification_dedupe_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_deleted BOOLEAN;
BEGIN
  -- A notification must never be written for a missing or soft-deleted tenant. Reading tenants
  -- through a SECURITY DEFINER function avoids relying on the caller's RLS tenant context.
  SELECT t.deleted INTO v_deleted FROM tenants t WHERE t.id = NEW.tenant_id;
  IF v_deleted IS NULL OR v_deleted THEN
    RAISE EXCEPTION 'Notification tenant % is missing or deleted.', NEW.tenant_id
      USING ERRCODE = '23514';
  END IF;

  IF NEW.dedupe_expires_at IS NULL OR NEW.dedupe_expires_at < NEW.created_at THEN
    RAISE EXCEPTION 'dedupe_expires_at (%) must not precede created_at (%).',
      NEW.dedupe_expires_at, NEW.created_at
      USING ERRCODE = '23514';
  END IF;

  -- Retire only terminal rows whose active window has already closed. Pending/retrying rows keep
  -- their key: a retry may still deliver them, and admitting a second delivery would double-send.
  -- The window boundary is inclusive: a row expires strictly before the new created_at, so two
  -- requests exactly at the TTL boundary still collapse into one row.
  UPDATE notification_deliveries
  SET dedupe_key = left(dedupe_key, 90) || '~' || replace(id::text, '-', '')
  WHERE tenant_id = NEW.tenant_id
    AND dedupe_key = NEW.dedupe_key
    AND status IN ('SENT', 'FAILED')
    AND dedupe_expires_at < NEW.created_at;

  RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_notification_dedupe_guard ON notification_deliveries;
CREATE TRIGGER trg_notification_dedupe_guard
    BEFORE INSERT ON notification_deliveries
    FOR EACH ROW
    EXECUTE FUNCTION awap_notification_dedupe_guard();

-- Retention: terminal rows are purged only after the audit window AND after the de-duplication
-- window has closed, so the purge can never reopen a still-active key (which would allow an
-- unexpected resend). Retry rows keep their shorter retention.
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
    AND updated_at < p_terminal_cutoff
    AND dedupe_expires_at < p_terminal_cutoff;
  GET DIAGNOSTICS v_terminal = ROW_COUNT;

  DELETE FROM notification_deliveries
  WHERE status IN ('PENDING', 'RETRYING')
    AND created_at < p_retry_cutoff;
  GET DIAGNOSTICS v_retry = ROW_COUNT;

  RETURN v_terminal + v_retry;
END
$$;

GRANT EXECUTE ON FUNCTION awap_purge_notification_deliveries(TIMESTAMPTZ, TIMESTAMPTZ) TO awap_app;
