-- V34: SUB_PROCESS parent/child linkage so a child instance can resume its parent node.

ALTER TABLE workflow_instances ADD COLUMN parent_instance_id UUID;
ALTER TABLE workflow_instances ADD COLUMN parent_node_id VARCHAR(100);

CREATE INDEX idx_workflow_instances_parent ON workflow_instances (parent_instance_id);
