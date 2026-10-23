-- V27: Partition outbox_events by created_at (monthly via a DEFAULT partition now,
-- explicit monthly partitions are added as data grows) and re-apply indexes + RLS.

ALTER TABLE outbox_events RENAME TO outbox_events_legacy;

CREATE TABLE outbox_events (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published BOOLEAN NOT NULL,
    created_by UUID
) PARTITION BY RANGE (created_at);

CREATE TABLE outbox_events_default PARTITION OF outbox_events DEFAULT;

INSERT INTO outbox_events
    SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json,
           created_at, published, created_by
    FROM outbox_events_legacy;

DROP TABLE outbox_events_legacy;

CREATE INDEX idx_outbox_events_published ON outbox_events (published, created_at);

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_isolation ON outbox_events
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
