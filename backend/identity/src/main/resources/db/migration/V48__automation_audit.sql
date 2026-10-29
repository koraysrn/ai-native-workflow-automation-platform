-- V48: Append-only audit for AI decisions/tool calls and SCRIPT executions (issue #132).
--
-- The table is deliberately write-once: the runtime role keeps INSERT/SELECT and loses
-- UPDATE/DELETE, so an audited decision cannot be rewritten after the fact.

CREATE TABLE automation_audit_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    instance_id UUID,
    node_id VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    action VARCHAR(32) NOT NULL,
    provider VARCHAR(64),
    model VARCHAR(64),
    prompt_hash VARCHAR(64),
    confidence NUMERIC(5, 4),
    tokens INTEGER NOT NULL DEFAULT 0,
    cost_micros BIGINT NOT NULL DEFAULT 0,
    outcome VARCHAR(32) NOT NULL,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_automation_audit_kind CHECK (kind IN ('AI', 'SCRIPT')),
    CONSTRAINT ck_automation_audit_outcome CHECK (
        outcome IN ('SUCCESS', 'BLOCKED', 'BUDGET_EXCEEDED', 'HITL', 'FAILED', 'TIMEOUT', 'CACHE_HIT')
    )
);

CREATE INDEX idx_automation_audit_tenant_created
    ON automation_audit_records (tenant_id, created_at DESC);

CREATE INDEX idx_automation_audit_instance
    ON automation_audit_records (instance_id, created_at);

ALTER TABLE automation_audit_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_audit_records FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_audit_records_isolation ON automation_audit_records
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- Immutability: append-only grants for the runtime role.
REVOKE UPDATE, DELETE ON automation_audit_records FROM awap_app;
GRANT SELECT, INSERT ON automation_audit_records TO awap_app;
