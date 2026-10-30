-- V49: Saga compensation for SUB_PROCESS and external integrations (issue #133).
--
-- saga_instances is one durable coordinator row per workflow instance. saga_key is unique per
-- tenant so a duplicate start returns the existing saga instead of starting a second one.
-- saga_steps stores every successfully executed action together with the compensation that undoes
-- it; steps are compensated in reverse step_order. The lease columns let the recovery scheduler
-- resume a crashed compensation exactly once, and next_compensation_at is the per-saga retry clock.

CREATE TABLE saga_instances (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    saga_key VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    next_compensation_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    compensation_started_at TIMESTAMPTZ,
    compensation_completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_saga_instances_tenant_key UNIQUE (tenant_id, saga_key),
    CONSTRAINT uq_saga_instances_tenant_instance UNIQUE (tenant_id, workflow_instance_id),
    CONSTRAINT ck_saga_instances_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED')
    )
);

CREATE INDEX idx_saga_instances_tenant_instance
    ON saga_instances (tenant_id, workflow_instance_id);
CREATE INDEX idx_saga_instances_due
    ON saga_instances (next_compensation_at)
    WHERE status = 'COMPENSATING';

CREATE TABLE saga_steps (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    saga_instance_id UUID NOT NULL REFERENCES saga_instances(id),
    step_order INTEGER NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    action_payload TEXT,
    compensation_type VARCHAR(50) NOT NULL,
    compensation_payload TEXT,
    idempotency_key VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    retry_backoff_ms BIGINT NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    last_error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    compensated_at TIMESTAMPTZ,
    CONSTRAINT uq_saga_steps_order UNIQUE (saga_instance_id, step_order),
    CONSTRAINT uq_saga_steps_idempotency UNIQUE (saga_instance_id, idempotency_key),
    CONSTRAINT ck_saga_steps_status CHECK (
        status IN ('SUCCEEDED', 'COMPENSATING', 'COMPENSATED', 'DLQ')
    ),
    CONSTRAINT ck_saga_steps_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_saga_steps_max_attempts_positive CHECK (max_attempts > 0),
    CONSTRAINT ck_saga_steps_retry_backoff_non_negative CHECK (retry_backoff_ms >= 0),
    CONSTRAINT ck_saga_steps_timeout_positive CHECK (timeout_seconds > 0)
);

CREATE INDEX idx_saga_steps_saga_order ON saga_steps (saga_instance_id, step_order);
CREATE INDEX idx_saga_steps_due
    ON saga_steps (next_attempt_at)
    WHERE status = 'COMPENSATING';

ALTER TABLE saga_instances ENABLE ROW LEVEL SECURITY;
ALTER TABLE saga_instances FORCE ROW LEVEL SECURITY;
CREATE POLICY saga_instances_isolation ON saga_instances
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE saga_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE saga_steps FORCE ROW LEVEL SECURITY;
CREATE POLICY saga_steps_isolation ON saga_steps
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- The recovery scheduler must see every tenant's due saga while awap_app is confined by RLS.
-- FOR UPDATE SKIP LOCKED plus the lease token keeps concurrent replicas apart; a crashed replica
-- leaves a stale lease that the next tick can take over.
CREATE OR REPLACE FUNCTION awap_claim_saga_compensations(
    p_now TIMESTAMPTZ,
    p_lease_token UUID,
    p_lease_seconds INTEGER,
    p_limit INTEGER)
RETURNS TABLE(tenant_id UUID, saga_instance_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    WITH picked AS (
        SELECT s.id
        FROM saga_instances s
        WHERE s.status = 'COMPENSATING'
          AND s.deleted = FALSE
          AND (s.next_compensation_at IS NULL OR s.next_compensation_at <= p_now)
          AND (s.lease_until IS NULL OR s.lease_until < p_now)
        ORDER BY s.next_compensation_at ASC NULLS FIRST, s.created_at ASC
        LIMIT GREATEST(p_limit, 1)
        FOR UPDATE SKIP LOCKED
    )
    UPDATE saga_instances s
    SET lease_token = p_lease_token,
        lease_until = p_now + make_interval(secs => GREATEST(p_lease_seconds, 1)),
        updated_at = p_now
    FROM picked p
    WHERE s.id = p.id
    RETURNING s.tenant_id, s.id;
END
$$;

REVOKE ALL ON FUNCTION awap_claim_saga_compensations(TIMESTAMPTZ, UUID, INTEGER, INTEGER)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_claim_saga_compensations(TIMESTAMPTZ, UUID, INTEGER, INTEGER)
    TO awap_app;
