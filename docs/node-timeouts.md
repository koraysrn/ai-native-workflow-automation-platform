# Node timeouts: guarantees, configuration and observability

Workflow nodes are executed by the engine in
[`NodeExecutor`](../backend/identity/src/main/java/com/awap/engine/application/NodeExecutor.java).
A timeout is resolved per node by
[`NodeTimeoutResolver`](../backend/identity/src/main/java/com/awap/engine/application/NodeTimeoutResolver.java),
measured and enforced by
[`NodeExecutionWatchdog`](../backend/identity/src/main/java/com/awap/engine/application/NodeExecutionWatchdog.java),
and persisted by
[`NodeTimeoutHandler`](../backend/identity/src/main/java/com/awap/engine/application/NodeTimeoutHandler.java).
The design rationale (and why `Thread.stop` is forbidden) is in
[ADR 0004](adr/0004-node-timeout-enforcement.md).

## Which node types have a hard guarantee?

| Node types | Enforcement | What actually happens on breach |
|---|---|---|
| `WEBHOOK`, `AI`, `SCRIPT` (reserved) | **ENFORCED** — hard guarantee | The outbound call runs on a bounded worker and is cancelled with `Future.cancel(true)`. The node is marked `TIMED_OUT` as soon as the budget expires (typically within a few ms of the budget). |
| `FORM`, `APPROVAL`, `TASK`, `DELAY`, `SUB_PROCESS`, `DECISION`, `CONDITION` | **BEST_EFFORT** — measured only | The work owns the JPA transaction and is never interrupted. Elapsed time is measured with `System.nanoTime()`; the breach is reported **after the work returns**, so the node's writes and the `TIMED_OUT` marker commit atomically. |
| `START`, `END`, `PARALLEL`, `PARALLEL_GATEWAY`, `MERGE`, `MERGE_GATEWAY` | **UNSUPPORTED** | There is no interruptible work. A configured `timeoutSeconds` is ignored and a warning is logged. |
| Any type not in the table | **UNSUPPORTED** | Fail-safe: the engine never claims a guarantee it cannot provide. |

`ENFORCED` is a *cancellation* guarantee. `BEST_EFFORT` is an *accounting* guarantee: the node is
still reported as `TIMED_OUT`, the instance fails (or is retried), but the code is not stopped.
This is deliberate — see the ADR.

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `awap.engine.node-timeout.default-seconds` | `NODE_TIMEOUT_DEFAULT_SECONDS` | `30` | Budget for nodes without `timeoutSeconds`. |
| `awap.engine.node-timeout.max-seconds` | `NODE_TIMEOUT_MAX_SECONDS` | `3600` | Upper bound for a node `timeoutSeconds`. |
| `awap.engine.node-timeout.worker-threads` | `NODE_TIMEOUT_WORKER_THREADS` | `8` | Bounded executor size for interrupt-safe work. |
| `awap.engine.node-timeout.queue-capacity` | `NODE_TIMEOUT_QUEUE_CAPACITY` | `64` | Queue length before submissions are rejected. |

Per node: `config_json.timeoutSeconds` (integer seconds).

Validation rules:

- `timeoutSeconds` must be ≥ **1** and ≤ `max-seconds`. `0`, negative values and absurd values
  (for example `86400` with the default maximum) are rejected with a validation error and the node
  is not executed.
- `default-seconds` must also be within `[1, max-seconds]`; invalid values fail application startup.
- Minimum 1 s exists on purpose: sub-second budgets mostly produce false failures under normal GC
  pauses.

## Outcomes

- `node_executions.status = TIMED_OUT` (`NodeExecutionStatus.TIMED_OUT`).
- `execution_logs.action = NODE_TIMED_OUT` with a JSON payload:
  `{"enforcement":"ENFORCED","hardGuarantee":true,"timeoutSeconds":1,"elapsedMs":1200,
  "attempt":1,"maxAttempts":2,"retryScheduled":true,"reason":"..."}`.
- Retry: a timeout is retried while `attempt < maxAttempts` by scheduling `resume_at`; the retry is
  applied **separately and idempotently** (an execution already `TIMED_OUT` is never re-scheduled).
- Terminal: the instance transitions to `FAILED` with action `NODE_TIMED_OUT`, and
  `awap.node.timeout.failures{type}` is incremented.
- Database-level timeouts (`SQLTimeoutException`, `jakarta.persistence.QueryTimeoutException`,
  `org.springframework.dao.QueryTimeoutException`) are classified as node timeouts too.
- `retry` / `recover` / `replay` re-drive both `FAILED` and `TIMED_OUT` executions.

## Metrics

| Metric | Type | Tags | Use |
|---|---|---|---|
| `awap.node.duration` | timer | `type` | Elapsed time of every measured node execution. |
| `awap.node.timeout` | counter | `type`, `enforcement`, `reason` (`enforced`, `best_effort`, `cancelled`, `database`) | Every breach, by detection path. |
| `awap.node.timeout.failures` | counter | `type` | Timeouts that exhausted the retry budget. |
| `awap.node.timeout.rejected` | counter | `type` | Work rejected by the saturated bounded executor. |
| `awap.node.timeout.abandoned` | gauge | — | Workers that ignored the interrupt and are still running. Non-zero is a leak. |

Alerts live in [`infra/prometheus/alert-rules.yml`](../infra/prometheus/alert-rules.yml).

## Structured log fields

Timeout warnings carry `workflowInstanceId`, `nodeId`, `nodeType`, `enforcement`, `timeoutMs`,
`elapsedMs`, `attempt`, `maxAttempts`, `retryScheduled`, `reason`, plus
`workerInterrupted`/`clearedStaleInterrupt` for enforced calls. The `UNSUPPORTED` warning carries
`nodeType`, `nodeId`, `enforcement` and `configuredTimeoutSeconds`.

## Choosing a budget

1. Start from the p99 latency of the node type's work (`awap.node.duration`).
2. Add headroom for GC and downstream retries (typically 2–3×).
3. Keep it below the caller's own HTTP timeout so the engine reports the failure first.
4. For long human waits (`APPROVAL`, `FORM`), the budget covers the *synchronous* engine step, not
   the waiting period — those nodes return `WAITING` immediately.
