-- Workflow Definition Service schema (Phase 0).

CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL,
    current_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE TABLE workflow_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_id UUID NOT NULL,
    version INTEGER NOT NULL,
    definition_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL,
    CONSTRAINT uq_workflow_versions UNIQUE (workflow_id, version)
);

CREATE INDEX idx_workflow_definitions_tenant ON workflow_definitions(tenant_id);
CREATE INDEX idx_workflow_versions_workflow ON workflow_versions(workflow_id);
