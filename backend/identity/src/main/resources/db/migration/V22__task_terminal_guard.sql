-- V22: Replace the ineffective V17 terminal guard (unique index on the PK) with a
-- real single-terminal-task guard keyed by the owning node execution. A workflow
-- node may create at most one terminal task, so this also prevents double
-- approval at the database level in addition to optimistic locking.
DROP INDEX IF EXISTS uq_tasks_terminal_once;

CREATE UNIQUE INDEX uq_tasks_node_terminal_once
    ON tasks (node_execution_id)
    WHERE status IN ('APPROVED', 'COMPLETED', 'REJECTED');
