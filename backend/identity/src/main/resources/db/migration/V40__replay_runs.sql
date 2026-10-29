-- V40: Immutable audit ledger for deterministic log replay runs (issue #116).
--
-- Every replay (dry-run and real) appends exactly one row so operators can prove that the
-- execution log of an instance reproduces the persisted WorkflowInstance/NodeExecution state.
-- The row is written in its own transaction so a divergence that aborts the replay is still
-- auditable.

CREATE TABLE replay_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    dry_run BOOLEAN NOT NULL,
    result VARCHAR(20) NOT NULL,
    divergence BOOLEAN NOT NULL,
    divergence_count INTEGER NOT NULL DEFAULT 0,
    divergence_detail TEXT,
    state_hash VARCHAR(64) NOT NULL,
    log_rows INTEGER NOT NULL DEFAULT 0,
    log_pages INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    requested_by UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_replay_runs_tenant_created
    ON replay_runs (tenant_id, created_at DESC);

CREATE INDEX idx_replay_runs_instance_created
    ON replay_runs (tenant_id, workflow_instance_id, created_at DESC);

ALTER TABLE replay_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE replay_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY replay_runs_isolation ON replay_runs
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
