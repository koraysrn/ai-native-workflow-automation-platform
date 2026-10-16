-- Human-authored entity audit columns (who created / last modified).
ALTER TABLE tenants ADD COLUMN created_by UUID;
ALTER TABLE tenants ADD COLUMN updated_by UUID;

ALTER TABLE users ADD COLUMN created_by UUID;
ALTER TABLE users ADD COLUMN updated_by UUID;

ALTER TABLE tasks ADD COLUMN created_by UUID;
ALTER TABLE tasks ADD COLUMN updated_by UUID;

ALTER TABLE workflow_definitions ADD COLUMN created_by UUID;
ALTER TABLE workflow_definitions ADD COLUMN updated_by UUID;

ALTER TABLE form_definitions ADD COLUMN created_by UUID;
ALTER TABLE form_definitions ADD COLUMN updated_by UUID;
