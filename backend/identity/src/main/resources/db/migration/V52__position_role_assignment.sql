-- V52 (#194): position-role assignment hardening and audit trail.
--
-- V20 already backfilled every existing position with the safe EMPLOYEE default. This migration
-- adds optimistic locking to positions, constrains the persisted role values and creates the
-- immutable audit table used for role changes.

ALTER TABLE positions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE positions ADD CONSTRAINT chk_positions_role CHECK (
    role IN ('SYSTEM_ADMIN', 'TENANT_ADMIN', 'MANAGER', 'OPERATIONS', 'EMPLOYEE')
);

CREATE TABLE organization_audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    old_value VARCHAR(255),
    new_value VARCHAR(255),
    actor_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_organization_audit_tenant_created
    ON organization_audit_events(tenant_id, created_at DESC);
CREATE INDEX idx_organization_audit_entity
    ON organization_audit_events(tenant_id, entity_type, entity_id);

ALTER TABLE organization_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_audit_isolation ON organization_audit_events
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY organization_audit_insert ON organization_audit_events FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

GRANT SELECT, INSERT ON organization_audit_events TO awap_app;
