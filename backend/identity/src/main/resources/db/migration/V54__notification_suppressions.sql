-- V54 (#204): bounce/complaint suppression list.
--
-- A hard bounce or a spam complaint is permanent: retrying it can only damage sender reputation.
-- The transport classifies those failures as permanent, records the recipient here, and every
-- later delivery to the same (tenant, channel, recipient) is dropped before the provider call.
-- The table is tenant-scoped and RLS-protected like every other notification table, so a bounce in
-- one tenant can never suppress a recipient of another tenant.

CREATE TABLE notification_suppressions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_suppressions UNIQUE (tenant_id, channel, recipient)
);

CREATE INDEX idx_notification_suppressions_tenant_created
    ON notification_suppressions (tenant_id, created_at DESC);

ALTER TABLE notification_suppressions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_suppressions FORCE ROW LEVEL SECURITY;

CREATE POLICY notification_suppressions_isolation ON notification_suppressions
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY notification_suppressions_insert ON notification_suppressions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY notification_suppressions_delete ON notification_suppressions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

GRANT SELECT, INSERT, DELETE ON notification_suppressions TO awap_app;
