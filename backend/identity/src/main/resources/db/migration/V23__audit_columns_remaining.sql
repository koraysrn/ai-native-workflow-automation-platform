-- V23: Extend created_by/updated_by audit columns to the remaining tenant tables.
-- V18 only covered tenants, users, tasks, workflow_definitions and form_definitions.

ALTER TABLE companies ADD COLUMN created_by UUID;
ALTER TABLE companies ADD COLUMN updated_by UUID;

ALTER TABLE departments ADD COLUMN created_by UUID;
ALTER TABLE departments ADD COLUMN updated_by UUID;

ALTER TABLE teams ADD COLUMN created_by UUID;
ALTER TABLE teams ADD COLUMN updated_by UUID;

ALTER TABLE positions ADD COLUMN created_by UUID;
ALTER TABLE positions ADD COLUMN updated_by UUID;

ALTER TABLE organization_users ADD COLUMN created_by UUID;
ALTER TABLE organization_users ADD COLUMN updated_by UUID;

ALTER TABLE workflow_versions ADD COLUMN created_by UUID;
ALTER TABLE workflow_versions ADD COLUMN updated_by UUID;

ALTER TABLE workflow_nodes ADD COLUMN created_by UUID;
ALTER TABLE workflow_nodes ADD COLUMN updated_by UUID;

ALTER TABLE workflow_transitions ADD COLUMN created_by UUID;
ALTER TABLE workflow_transitions ADD COLUMN updated_by UUID;

ALTER TABLE workflow_variables ADD COLUMN created_by UUID;
ALTER TABLE workflow_variables ADD COLUMN updated_by UUID;

ALTER TABLE execution_contexts ADD COLUMN created_by UUID;
ALTER TABLE execution_contexts ADD COLUMN updated_by UUID;

ALTER TABLE node_executions ADD COLUMN created_by UUID;
ALTER TABLE node_executions ADD COLUMN updated_by UUID;

ALTER TABLE execution_logs ADD COLUMN created_by UUID;
ALTER TABLE execution_logs ADD COLUMN updated_by UUID;

ALTER TABLE form_submissions ADD COLUMN created_by UUID;
ALTER TABLE form_submissions ADD COLUMN updated_by UUID;

ALTER TABLE file_records ADD COLUMN created_by UUID;
ALTER TABLE file_records ADD COLUMN updated_by UUID;

ALTER TABLE outbox_events ADD COLUMN created_by UUID;
ALTER TABLE outbox_events ADD COLUMN updated_by UUID;

ALTER TABLE idempotency_records ADD COLUMN created_by UUID;
ALTER TABLE idempotency_records ADD COLUMN updated_by UUID;

ALTER TABLE refresh_tokens ADD COLUMN created_by UUID;
ALTER TABLE refresh_tokens ADD COLUMN updated_by UUID;
