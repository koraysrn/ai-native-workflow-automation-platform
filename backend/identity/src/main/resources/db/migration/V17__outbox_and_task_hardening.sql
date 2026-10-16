-- Durable outbox for domain events + task service hardening.

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published BOOLEAN NOT NULL
);

CREATE INDEX idx_outbox_events_published ON outbox_events(published, created_at);

-- Task: link to the engine node execution that created it, escalation deadline and
-- a DB-level guard that prevents double completion of the same task.
ALTER TABLE tasks ADD COLUMN node_execution_id UUID;
ALTER TABLE tasks ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tasks ADD COLUMN escalate_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN completed_at TIMESTAMPTZ;

-- Optimistic double-approval guard: only one COMPLETED/APPROVED terminal row per task id.
CREATE UNIQUE INDEX uq_tasks_terminal_once ON tasks (id) WHERE status IN ('APPROVED', 'COMPLETED');

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_isolation ON outbox_events
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
