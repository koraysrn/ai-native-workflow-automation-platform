-- V55: Durable idempotency guard for deadline-driven task escalations (issue #207).
--
-- The scheduler marks the task escalated when it publishes TASK_ESCALATION_DUE. That flag cannot
-- double as the "already reassigned" guard because it is set before the event is consumed, so a
-- duplicate Kafka record would reassign and notify twice. escalation_applied_at is written by the
-- consumer in the same transaction as the reassignment and the notification request.
ALTER TABLE tasks
    ADD COLUMN escalation_applied_at TIMESTAMPTZ;

CREATE INDEX idx_tasks_escalation_pending
    ON tasks (tenant_id, escalate_at)
    WHERE escalation_applied_at IS NULL AND deleted = FALSE;
