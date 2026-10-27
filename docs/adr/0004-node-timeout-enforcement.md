# ADR 0004 — Node timeout enforcement: no `Thread.stop`, explicit guarantees per node type

- **Status:** Accepted
- **Date:** 2026-10-27
- **Issue:** #115 — "Make generic node timeout safe and measurable"
- **Supersedes:** nothing
- **Related:** [`docs/node-timeouts.md`](../node-timeouts.md),
  [`NodeExecutionWatchdog`](../../backend/identity/src/main/java/com/awap/engine/application/NodeExecutionWatchdog.java),
  [`NodeTimeoutPolicy`](../../backend/identity/src/main/java/com/awap/engine/application/NodeTimeoutPolicy.java)

## Context

Until this change only two code paths had a timeout: the outbound webhook HTTP call
(`WebhookClient`/`WebhookInvoker`) and the AI layer client. Every other node type — `FORM`,
`APPROVAL`, `TASK`, `SUB_PROCESS`, `DECISION`, gateways, and any Java code that will be added
later (`SCRIPT`) — executed synchronously with no time limit at all.

Workflow nodes run inside the engine's JPA transaction and a single node dispatch mutates managed
entities, writes node executions and drives transitions. Arbitrary Java code that owns the current
transaction **cannot be safely stopped from another thread**:

- Interrupting a thread in the middle of a JDBC round-trip leaves the connection, the transaction
  and Hibernate's persistence context in an unknown state.
- `Thread.stop` releases every monitor the victim holds, so invariants guarded by `synchronized`
  are violated and shared structures (entity collections, caches) can be left half-updated.
- The victim may be terminated exactly between "database write completed" and "outcome recorded",
  producing work without a record — the opposite of the engine's exactly-once node contract.

The product still needs a bounded, *measurable* and *auditable* timeout for every node, plus an
honest statement of where the guarantee holds.

## Decision

1. **Explicit policy per node type.** `NodeTimeoutPolicy` maps every node type to `ENFORCED`,
   `BEST_EFFORT` or `UNSUPPORTED`. Unknown node types are `UNSUPPORTED` (fail-safe: never claim a
   guarantee the engine cannot provide). `SCRIPT` is reserved as `ENFORCED` before the node type
   exists, because the constitution requires the script sandbox to be time-boxed.
2. **`ENFORCED` only for interrupt-safe work.** `WEBHOOK` and `AI` (and the future `SCRIPT`
   sandbox) perform outbound work that can be abandoned. The *call alone* is submitted to a
   bounded, daemon, core-timeout `ThreadPoolExecutor` and awaited for at most the node budget.
   On breach the future is cancelled with `Future.cancel(true)`. All persistence for the node
   (completing the execution, writing delivery records) stays on the caller thread, inside the
   transaction.
3. **`BEST_EFFORT` for transaction-bound node types.** The work stays on the caller thread
   (`FORM`, `APPROVAL`, `TASK`, `SUB_PROCESS`, `DELAY`, `DECISION`, `CONDITION`). The watchdog
   measures elapsed time with `System.nanoTime()` and reports the breach **only after the work
   actually returns**, so the commit/rollback decision is never taken for a half-executed unit of
   work. `System.nanoTime()` is used instead of the wall clock so a backwards clock jump cannot
   produce a negative duration.
4. **`UNSUPPORTED` for structural nodes.** `START`, `END` and the gateway nodes have no
   interruptible work; a configured `timeoutSeconds` is ignored and a warning (with structured
   fields) is logged instead of pretending to enforce anything.
5. **`TIMED_OUT` is a first-class outcome.** `NodeExecutionStatus.TIMED_OUT`, a
   `NODE_TIMED_OUT` execution-log row with structured JSON payload, and `instance FAILED` when the
   retry budget is exhausted. The retry policy is applied separately and idempotently: a timeout
   schedules at most one resume, and an execution already marked `TIMED_OUT` is never re-scheduled
   or counted twice. `FAILED` and `TIMED_OUT` are both re-drivable by retry/recover/replay.
6. **No `Thread.stop`, ever.** Not for `ENFORCED` nodes, not for `BEST_EFFORT` nodes. The JVM
   deprecates it for removal precisely because it destroys object invariants. `Future.cancel(true)`
   is cooperative: well-behaved code observes the interrupt and unwinds normally.
7. **Interrupt handling is explicit.** The waiting thread never inherits the worker's interrupt
   status; a stale flag is cleared (and reported in the log) so the next node executes normally.
   When the *waiting* thread is interrupted, the flag is restored and
   `NodeInterruptedExecutionException` is raised — the interrupt is never swallowed. Worker threads
   clear their own flag before returning to the pool so a pooled thread cannot poison the next task.
8. **Bounded executor with graceful shutdown.** Worker count and queue length are configurable and
   validated; saturation is a controlled `NodeTimeoutRejectedException` (counted in
   `awap.node.timeout.rejected`) instead of unbounded queue growth. Threads are daemons with
   `allowCoreThreadTimeOut(true)`, so an idle JVM holds none. `shutdown()` drains, then interrupts
   stragglers. A worker that swallows its interrupt stays visible in the
   `awap.node.timeout.abandoned` gauge (the thread-leak alert) until it exits.
9. **Configuration is validated, not clamped.** `awap.engine.node-timeout.default-seconds`
   (default 30 s) and per-node `timeoutSeconds` must be ≥ 1 s and ≤
   `awap.engine.node-timeout.max-seconds` (default 3600 s). Zero, negative and absurd values are
   rejected with `AWAP-…-VALIDATION_FAILED`; a bad default fails application startup. Webhook retry
   behaviour and all existing APIs are unchanged.

## Consequences

### Positive

- Every node has a measured duration (`awap.node.duration{type=…}`) and every breach is counted
  (`awap.node.timeout{type,enforcement,reason}`), so "slow workflow" is diagnosable.
- `WEBHOOK`/`AI` calls are hard-bounded, and their retries are idempotent (stable
  `instanceId:nodeId:executionId` key).
- The transaction contract is preserved: no code that touches JPA is ever cancelled mid-flight, so
  there is no window where work commits without its `TIMED_OUT`/`FAILED` record.

### Negative / accepted limitations

- A `BEST_EFFORT` node is **not stopped**. It runs to completion and is then marked `TIMED_OUT`;
  its work and the timeout marker commit atomically. This is a measurement + failure classification,
  not an execution cancellation. Operators must not treat it as a kill switch.
- A worker that ignores interrupts keeps running until it cooperatively exits. The engine protects
  itself with the bounded pool and exposes the leak through the `abandoned` gauge, but it cannot
  forcibly terminate the thread.
- Because node results are persisted in the engine's transaction, a *database-level* abort
  (e.g. a PostgreSQL statement timeout) rolls the transaction back; the `TIMED_OUT` marker is then
  written by the subsequent `retry`/`recover` path instead of being persisted out-of-band. Writing
  it from a second connection would either block on the uncommitted row or create a duplicate
  identity, so it is deliberately avoided.
- A retried timeout re-executes the node. Side effects are made safe by idempotency keys, not by
  the timeout mechanism.

## The async job/queue model will replace synchronous cancellation

Synchronous cancellation is a stop-gap. The target architecture makes node execution a durable
**job**, not a thread inside an HTTP request:

1. A node is enqueued as a lease-bearing job row (the same pattern already used by the durable
   webhook delivery queue: claim with `FOR UPDATE SKIP LOCKED`, bounded lease, attempt counter).
2. A worker stage executes it and renews the lease; the timeout becomes **lease expiry**.
3. On expiry any replica can re-claim the job; replay is safe because node execution is
   deterministic and external calls carry idempotency keys.
4. Cancellation therefore becomes "stop renewing + let another worker re-drive", which is
   crash-consistent and needs no thread interruption at all.
5. Only then can the engine guarantee *hard* timeouts for DB/JPA nodes, because the transaction is
   no longer tied to the caller's thread and the partial work is rolled back by the worker's
   transaction boundary.

Until that model lands, this ADR is the contract: `ENFORCED` is a hard guarantee, everything else
is best effort and is documented as such.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `Thread.stop` | Releases monitors, corrupts invariants, deprecated for removal, can be swallowed (`ThreadDeath`). Cannot be used against JPA/JDBC work. |
| `Thread.interrupt()` on the caller thread for every node | The caller *is* the transaction. Interrupting it aborts a JDBC call mid-statement and leaves the persistence context inconsistent. |
| Run every node on the executor | Breaks transaction propagation (Spring's transaction is thread-bound), so node writes would not be atomic with the instance state. |
| Kill the JVM / request timeout at the HTTP layer | Loses in-flight state, does not record `TIMED_OUT`, and cannot protect background/scheduled executions. |
| Clamp invalid timeouts silently | Hides a workflow-definition bug and makes behaviour unpredictable; validation is required by the issue. |

## Verification

- Real-duration tests: 2 s blocking work with a 1 s budget must be abandoned between 1 s and
  1.5 s; a fast node must not time out; 50 concurrent timeouts on a bounded pool must all be
  classified; a saturated pool must reject in a controlled way; shutdown must leave no thread leak
  (difference ≤ 2); a worker that swallows interrupts must stay in the `abandoned` gauge.
- Engine tests: `TIMED_OUT` execution, `NODE_TIMED_OUT` log, `instance FAILED`, retry up to
  `maxAttempts` with a single idempotency key, interrupt flag cleared before the next node, and a
  warning path for `UNSUPPORTED` node types.
- Persistence test: a database write that hits a statement timeout rolls back completely, then the
  timeout outcome is persisted with the real repositories.
