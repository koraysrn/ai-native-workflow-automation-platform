-- V38: Durable webhook delivery queue / dead-letter pipeline for WEBHOOK workflow nodes.
--
-- A WEBHOOK node performs its first HTTP attempt synchronously. When that attempt fails with a
-- retryable error the delivery is persisted here and a leased scheduler (fixed 5s delay) performs
-- exactly one further attempt per claim. The unique (tenant_id, idempotency_key) constraint keeps
-- receiver-side de-duplication stable across every attempt and across replicas.

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    node_execution_id UUID NOT NULL,
    node_id VARCHAR(255) NOT NULL,
    target_url TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_token VARCHAR(64),
    last_status_code INTEGER,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_webhook_deliveries_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_webhook_deliveries_status
        CHECK (status IN ('PENDING', 'RETRYING', 'SENT', 'DEAD', 'CANCELLED')),
    CONSTRAINT ck_webhook_deliveries_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_webhook_deliveries_max_attempts CHECK (max_attempts BETWEEN 1 AND 10)
);

-- Due-row scan: partial index keeps the claim query narrow as SENT/DEAD history grows.
CREATE INDEX idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_webhook_deliveries_tenant_status
    ON webhook_deliveries (tenant_id, status, created_at DESC);

CREATE INDEX idx_webhook_deliveries_tenant_instance
    ON webhook_deliveries (tenant_id, workflow_instance_id);

ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_deliveries FORCE ROW LEVEL SECURITY;

CREATE POLICY webhook_deliveries_select ON webhook_deliveries FOR SELECT
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY webhook_deliveries_insert ON webhook_deliveries FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY webhook_deliveries_update ON webhook_deliveries FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY webhook_deliveries_delete ON webhook_deliveries FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- The retry scheduler is a system task: it must claim due rows across every tenant while the
-- runtime role (awap_app) is constrained by RLS and has no tenant context. Claiming the batch
-- atomically inside the function (FOR UPDATE SKIP LOCKED + a per-claim lease token) guarantees
-- that two replicas never attempt the same row. search_path is pinned against pg_temp shadowing.
CREATE OR REPLACE FUNCTION awap_claim_due_webhook_deliveries(
    p_now TIMESTAMPTZ,
    p_limit INT,
    p_lease_seconds INT)
RETURNS SETOF webhook_deliveries
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  RETURN QUERY
  WITH due AS (
    SELECT d.id
    FROM webhook_deliveries d
    WHERE d.status IN ('PENDING', 'RETRYING')
      AND d.next_attempt_at <= p_now
      AND d.expires_at > p_now
      AND (d.lease_until IS NULL OR d.lease_until <= p_now)
    ORDER BY d.next_attempt_at ASC
    LIMIT GREATEST(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  )
  UPDATE webhook_deliveries d
  SET attempts = d.attempts + 1,
      lease_token = gen_random_uuid()::text,
      lease_until = p_now + make_interval(secs => GREATEST(p_lease_seconds, 1)),
      status = 'RETRYING',
      updated_at = p_now
  FROM due
  WHERE d.id = due.id
  RETURNING d.*;
END
$$;

GRANT EXECUTE ON FUNCTION awap_claim_due_webhook_deliveries(TIMESTAMPTZ, INT, INT) TO awap_app;
