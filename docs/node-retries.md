# Per-node retry policies (issue #210)

Every executable node type (`TASK`, `WEBHOOK`, `AI`, `SCRIPT`, `SUB_PROCESS`, …) runs under a
configurable retry policy. Failures are classified before a retry is scheduled: transient errors are
retried outside the request thread, permanent errors and exhausted budgets are published to the node
DLQ (`workflow.node.dlq`) and fail the workflow visibly.

## Configuration

The policy lives inside the node's `config.retryPolicy` block:

```json
{
  "id": "call-crm",
  "type": "WEBHOOK",
  "config": {
    "url": "https://crm.example.com/hook",
    "retryPolicy": {
      "maxAttempts": 3,
      "delaysMs": [1000, 5000, 25000],
      "jitterRatio": 0.2,
      "retryableErrorClasses": ["TimeoutException", "IOException", "GENERIC_INTERNAL_ERROR"]
    }
  }
}
```

| Field | Meaning | Validation |
|---|---|---|
| `maxAttempts` | Total attempt budget, including the first attempt | 1–20 |
| `delaysMs` | Delay before each subsequent attempt; the last entry is reused | 1–20 entries, each 1 ms–1 h |
| `backoffMs` | Single delay when `delaysMs` is absent (default 1000) | 1 ms–1 h |
| `jitterRatio` | ± fraction applied to each delay to avoid retry storms | 0–1 |
| `retryableErrorClasses` | Class simple names, full class names or `ErrorCode` names that may be retried | non-blank entries |

A malformed policy is rejected when the workflow definition is saved (`POST`/`PUT`
`/api/v1/workflow-definitions`), never at execution time. Nodes without a `retryPolicy` keep the
legacy top-level `maxAttempts` (default 3) and `retryBackoffMs` (default 1000) behaviour.

## Classification

- Safety errors are always permanent, even if listed:
  `VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN`, `WORKFLOW_INVALID_DEFINITION`,
  `WORKFLOW_VERSION_*`, `TASK_ACCESS_DENIED`.
- When `retryableErrorClasses` is configured, only a matching class (walking the cause chain) or
  `ErrorCode` name is retried.
- Without a list, every non-permanent failure is retryable.

## Scheduling and persistence

On a retryable failure the engine keeps the same `node_executions` row (unique per instance+node),
increments `attempt`, persists `resume_at = now + delay` (jittered, never in the past) and leaves the
workflow running. `DelayScheduler` polls due rows every 5 seconds and re-drives the node outside the
request thread; the stable `idempotencyKey` (`workflowInstanceId:nodeId`) is logged with every
retry so an external side effect can deduplicate.

A paused workflow is never retried: `executeNode` returns before touching the execution row.

## Exhaustion and the node DLQ

When the budget is spent (`EXHAUSTED`) or the error is permanent (`PERMANENT`, including replay
suppression), the engine:

1. marks the execution `FAILED`,
2. publishes a `NODE_DLQ` outbox event to `workflow.node.dlq` with `tenantId`,
   `workflowInstanceId`, `nodeId`, `nodeType`, `attempts`, `maxAttempts`, `reason`, `error` and
   `idempotencyKey`,
3. increments `awap_node_retry_exhausted_total{nodeType,reason}`,
4. transitions the workflow to `FAILED` with `NODE_FAILED`.

`AwapNodeRetryExhausted` fires on any increase of the counter. The `awap-dlq` consumer also counts
the record (`awap_dlq_messages_total{topic="workflow.node.dlq"}`), so the per-topic DLQ dashboard and
the existing DLQ alerts cover it.

## Tests

- `NodeRetryPolicyTest` — parsing, delay sequence, jitter bounds, permanent/retryable classification
  and every validation branch.
- `NodeExecutorRetryPolicyTest` — retry scheduling with persisted attempt/next-attempt time, stable
  idempotency key, permanent/exhausted node DLQ, paused workflows and a policy applied to every
  executable node type.
- `NodeConfigReaderTest` / `WorkflowDefinitionServiceNegativeTest` — config parsing and
  save-time rejection of malformed policies.
