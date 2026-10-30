# Saga compensation for SUB_PROCESS and external integrations (#133)

The workflow engine is the saga orchestrator. It records every successful external action that
declares a compensating transaction, and it undoes those actions in reverse order when a later node
fails. State is durable in PostgreSQL, so a crash mid-compensation resumes from the first
uncompensated step instead of repeating already-completed rollbacks.

## 1. Model

| Table | Purpose |
|---|---|
| `saga_instances` | One coordinator row per workflow instance. `(tenant_id, saga_key)` is unique, so a duplicate start returns the existing saga. Holds status, failure reason, the retry clock (`next_compensation_at`), the recovery lease and optimistic `version`. |
| `saga_steps` | Every successfully executed action: `action_type`, `action_payload`, `compensation_type`, `compensation_payload`, idempotency key, retry policy and status. Steps are compensated in descending `step_order`. |

`SagaStep.goal` is expressed in the node config:

```json
{
  "url": "https://erp.example.com/orders",
  "compensation": {
    "type": "WEBHOOK",
    "url": "https://erp.example.com/orders/rollback",
    "payload": "{\"orderId\":\"{{orderId}}\"}",
    "timeoutSeconds": 10,
    "maxAttempts": 3,
    "retryBackoffMs": 1000
  }
}
```

SUB_PROCESS nodes do not need a `compensation` block: their compensation is the child saga. When
the child finishes successfully, the parent records a `SUB_PROCESS` step with the child instance id.
If the parent later fails, the child saga is reopened and compensated before the parent continues to
its own earlier steps.

## 2. Status transitions

Saga: `RUNNING -> COMPLETED` on workflow success, `RUNNING -> COMPENSATING -> COMPENSATED` on a
handled failure, and `COMPENSATING -> FAILED` when a step exhausts its retry budget.

Step: `SUCCEEDED -> COMPENSATING -> COMPENSATED`, or `COMPENSATING -> DLQ` after exhaustion. A
`DLQ` step can be re-armed by the manual intervention API. Every transition writes an append-only
`execution_logs` row (`SAGA_STEP_SUCCEEDED`, `SAGA_COMPENSATION_STARTED`,
`SAGA_COMPENSATION_COMPLETED`, `SAGA_COMPENSATION_RETRY_SCHEDULED`, `SAGA_COMPENSATION_DLQ`).

## 3. Reverse ordering, retries and idempotency

- Compensation walks `saga_steps` from the highest `step_order` downwards. A lower step is never
  attempted while a higher step is still uncompensated.
- Each compensation uses the step's stable idempotency key (`saga:<sagaId>:node:<nodeId>:<executionId>`)
  as the `Idempotency-Key` header. Retries therefore ask the external system to de-duplicate the
  rollback instead of performing it twice.
- `timeoutSeconds` bounds one outbound call. `maxAttempts` and `retryBackoffMs` control the retry
  budget. Exhausted steps become `DLQ`, the saga becomes `FAILED`, and a
  `SAGA_COMPENSATION_DLQ` outbox event is published to `workflow.saga.events.dlq`.
- A crash after a step was committed leaves the saga `COMPENSATING`; the recovery scheduler claims
  it through `awap_claim_saga_compensations` (`FOR UPDATE SKIP LOCKED` + lease) and resumes the first
  uncompensated step.

## 4. Which integrations require compensation

| Integration | Compensation | Why |
|---|---|---|
| ERP order/invoice/payment creation | Required (`WEBHOOK`) | A partial order or invoice cannot be left behind when a later CRM step fails. |
| CRM lead/opportunity/contact creation | Required (`WEBHOOK`) | Duplicate customer records and orphaned opportunities are hard to clean up manually. |
| Generic outbound webhook with a mutating side effect | Required (`WEBHOOK`) | The receiver may have persisted the request even when the workflow later fails. |
| Read-only lookup, notification-only webhook | Not required | Nothing durable to undo. |
| SUB_PROCESS | Required (automatic) | The child may have created its own external effects; compensating the child saga is the rollback. |
| AI/SCRIPT nodes | Required only when the downstream call mutates external state and declares a `WEBHOOK` compensation | The node itself is deterministic; the external side effect is what needs a rollback. |

## 5. Manual intervention API

- `GET /api/v1/workflow-instances/{id}/saga` (`INSTANCE_READ`) returns the saga and its steps.
- `POST /api/v1/workflow-instances/{id}/saga/compensate` (`INSTANCE_MANAGE`) starts compensation or
  reopens every `DLQ` step after the external system was fixed.
- `POST /api/v1/workflow-instances/{id}/saga/steps/{stepId}/retry` (`INSTANCE_MANAGE`) re-arms one
  step. A lower step is rejected with `409 AWAP-ENG-0015` while a later step is uncompensated.

All endpoints are tenant-scoped: a saga of another tenant is indistinguishable from a missing saga
(`404 AWAP-ENG-0013`). See `docs/openapi/awap-openapi.yaml` for the schemas.

## 6. Metrics and alerts

| Micrometer meter | Prometheus | Meaning |
|---|---|---|
| `awap.saga.steps{outcome}` | `awap_saga_steps_total` | `succeeded`, `compensated` and `failed` step outcomes. |
| `awap.saga.compensation.retries` | `awap_saga_compensation_retries_total` | Compensation attempts that were rescheduled. |
| `awap.saga.duration{status}` | `awap_saga_duration_seconds_*` | End-to-end saga duration tagged with the terminal status. |

`AwapSagaCompensationFailures` fires when any compensation step fails in the last ten minutes; the
runbook path is `docs/operations-runbook.md#o18--saga-compensation-issue-133`.

## 7. Rollback and data migration

V49 is additive: it creates the two tables, their RLS policies and one SECURITY DEFINER claim
function. Existing workflows keep running unchanged because a node without a `compensation` block is
not recorded. To disable the feature, stop sending new `compensation` blocks and set
`SAGA_SCHEDULER_ENABLED=false`; already-compensating sagas can still be finished with the manual
API. No down-migration is required because the tables are not read by older application versions.
