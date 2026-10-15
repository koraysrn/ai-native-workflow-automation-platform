-- Workflow Engine schema (Phase 0).

CREATE TABLE workflow_instances (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_definition_id UUID NOT NULL,
    workflow_version INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    context_json TEXT,
    idempotency_key VARCHAR(255),
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL,
    CONSTRAINT uq_workflow_instances_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE execution_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    node_id VARCHAR(100),
    node_type VARCHAR(50),
    action VARCHAR(100) NOT NULL,
    payload TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_workflow_instances_tenant ON workflow_instances(tenant_id);
CREATE INDEX idx_execution_logs_instance ON execution_logs(workflow_instance_id);
