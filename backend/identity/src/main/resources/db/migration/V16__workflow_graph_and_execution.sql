-- Workflow graph (nodes/transitions/variables) and runtime execution model.

CREATE TABLE workflow_nodes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    config_json TEXT,
    order_index INTEGER NOT NULL,
    CONSTRAINT uq_workflow_nodes UNIQUE (workflow_version_id, node_id)
);

CREATE TABLE workflow_transitions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    from_node_id VARCHAR(100) NOT NULL,
    to_node_id VARCHAR(100) NOT NULL,
    condition_expression VARCHAR(1000)
);

CREATE TABLE workflow_variables (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    data_type VARCHAR(50) NOT NULL,
    default_value VARCHAR(1000),
    CONSTRAINT uq_workflow_variables UNIQUE (workflow_version_id, name)
);

CREATE TABLE execution_contexts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    variables_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_execution_contexts UNIQUE (workflow_instance_id)
);

CREATE TABLE node_executions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt INTEGER NOT NULL,
    output_json TEXT,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_node_executions UNIQUE (workflow_instance_id, node_id)
);

CREATE INDEX idx_workflow_nodes_version ON workflow_nodes(workflow_version_id);
CREATE INDEX idx_workflow_transitions_version ON workflow_transitions(workflow_version_id);
CREATE INDEX idx_workflow_variables_version ON workflow_variables(workflow_version_id);
CREATE INDEX idx_execution_contexts_instance ON execution_contexts(workflow_instance_id);
CREATE INDEX idx_node_executions_instance ON node_executions(workflow_instance_id);

-- Tenant isolation for the new tables (SELECT + INSERT/UPDATE via WITH CHECK).
ALTER TABLE workflow_nodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_nodes FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_nodes_isolation ON workflow_nodes
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE workflow_transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_transitions FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_transitions_isolation ON workflow_transitions
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE workflow_variables ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_variables FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_variables_isolation ON workflow_variables
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE execution_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_contexts FORCE ROW LEVEL SECURITY;
CREATE POLICY execution_contexts_isolation ON execution_contexts
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE node_executions ENABLE ROW LEVEL SECURITY;
ALTER TABLE node_executions FORCE ROW LEVEL SECURITY;
CREATE POLICY node_executions_isolation ON node_executions
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
