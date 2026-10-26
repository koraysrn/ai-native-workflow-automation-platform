-- V35: Durable notification delivery ledger for idempotency, audit and the in-app inbox.

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    dedupe_key VARCHAR(128) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    template_key VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_deliveries UNIQUE (tenant_id, dedupe_key)
);

CREATE INDEX idx_notification_deliveries_tenant_created
    ON notification_deliveries (tenant_id, created_at DESC);

ALTER TABLE notification_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_deliveries_isolation ON notification_deliveries
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
