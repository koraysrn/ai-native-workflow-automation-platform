-- Task Service schema (Phase 0).

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    assignee_id UUID,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE INDEX idx_tasks_tenant ON tasks(tenant_id);
CREATE INDEX idx_tasks_assignee ON tasks(assignee_id);
