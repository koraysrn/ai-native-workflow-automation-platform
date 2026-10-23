-- V28: Persistent resume scheduling for the DELAY node.
-- The engine stores a UTC resume instant; the DelayScheduler polls due rows.
ALTER TABLE node_executions ADD COLUMN resume_at TIMESTAMPTZ;

CREATE INDEX idx_node_executions_resume_at
    ON node_executions (resume_at)
    WHERE resume_at IS NOT NULL;
