-- V33: Document module versioned-storage metadata (ADR-003).
CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    file_id UUID NOT NULL,
    version INTEGER NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    format VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (file_id, version)
);

ALTER TABLE document_versions ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_versions_select ON document_versions FOR SELECT
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY document_versions_insert ON document_versions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY document_versions_update ON document_versions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY document_versions_delete ON document_versions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
