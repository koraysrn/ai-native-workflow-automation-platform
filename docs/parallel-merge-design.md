# Parallel Gateway & Merge Design

## Model

The engine already records every node execution in `node_executions`
([`NodeExecution`](../backend/identity/src/main/java/com/awap/engine/domain/NodeExecution.java)).
For `PARALLEL_GATEWAY` / `MERGE_GATEWAY` we track branch tokens explicitly.

- `branch_token_count` — number of outgoing branches a fork opened.
- `merged_branch_count` — number of branches that have reached the join.
- `join_node_id` — the `MERGE_GATEWAY` node id a branch must report to.

A `PARALLEL_GATEWAY` node creates one child token per outgoing transition and
persists the branch bookkeeping before dispatching children. Each terminal
child (or nested join) increments `merged_branch_count` on the owning join
record. The join fires **only when** `merged_branch_count == branch_token_count`.

## Invariants

1. A join never fires before every opened branch has completed.
2. A failed branch marks the join failed; the instance transitions to `FAILED`.
3. Bookkeeping is written in the same transaction as the node transition.
4. The algorithm is deterministic: same input + same event order yields the
   same token counts (required by replay/recovery).

## Replay / recovery interaction

- During replay, token counters are re-derived from the recorded execution log,
  not incremented again.
- Recovery of a failed parallel branch resumes only that branch; the join waits
  for the remaining tokens.

## Migration

See `V29__parallel_gateway.sql` for the `node_executions` columns.
