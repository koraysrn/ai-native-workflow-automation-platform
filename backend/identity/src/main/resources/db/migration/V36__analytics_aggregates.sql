-- V36: Tenant/day analytics aggregates for the dashboard (raw events stay in OpenSearch).

CREATE TABLE analytics_aggregates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    day DATE NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    event_count BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_analytics_aggregates UNIQUE (tenant_id, day, event_type)
);

CREATE INDEX idx_analytics_aggregates_tenant_day
    ON analytics_aggregates (tenant_id, day);

ALTER TABLE analytics_aggregates ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics_aggregates FORCE ROW LEVEL SECURITY;
CREATE POLICY analytics_aggregates_isolation ON analytics_aggregates
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
