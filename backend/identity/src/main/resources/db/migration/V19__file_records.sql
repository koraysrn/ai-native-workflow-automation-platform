-- MinIO-backed file records.
CREATE TABLE file_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    size BIGINT NOT NULL,
    bucket VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_file_records_tenant ON file_records(tenant_id);

ALTER TABLE file_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_records FORCE ROW LEVEL SECURITY;
CREATE POLICY file_records_isolation ON file_records
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
