-- V41: quarantine -> scan -> promote pipeline for document/file uploads (#118).
-- Additive only: new columns keep legacy rows readable; legacy PENDING rows are fail-closed
-- because their bytes were written straight to awap-files before scanning.

ALTER TABLE file_records ADD COLUMN promoted_bucket VARCHAR(100);
ALTER TABLE file_records ADD COLUMN promoted_object_key VARCHAR(500);
ALTER TABLE file_records ADD COLUMN promoted_at TIMESTAMPTZ;
ALTER TABLE file_records ADD COLUMN scan_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE file_records ADD COLUMN last_scan_error VARCHAR(500);
ALTER TABLE file_records ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Legacy rows: CLEAN files were already approved and live in awap-files; PENDING rows were never
-- scanned and must not be downloadable, so they stay failed until re-uploaded.
UPDATE file_records
   SET status = 'PROMOTED',
       promoted_bucket = bucket,
       promoted_object_key = object_key,
       promoted_at = created_at
 WHERE status = 'CLEAN';

UPDATE file_records
   SET status = 'SCAN_FAILED',
       last_scan_error = 'Legacy upload predates quarantine; re-upload required.'
 WHERE status = 'PENDING';

CREATE INDEX idx_file_records_status ON file_records(status);
CREATE INDEX idx_file_records_tenant_status ON file_records(tenant_id, status);

-- Immutable audit trail for infected/scan-failed/promoted outcomes.
CREATE TABLE file_scan_audit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    file_id UUID NOT NULL,
    event VARCHAR(32) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_file_scan_audit_tenant_file ON file_scan_audit(tenant_id, file_id);

ALTER TABLE file_scan_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_scan_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY file_scan_audit_select ON file_scan_audit FOR SELECT
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY file_scan_audit_insert ON file_scan_audit FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
