# Notification producers (issue #203)

Task and workflow lifecycle events now publish a durable `NOTIFICATION_REQUESTED` outbox record, so
the notification service is fed by production traffic instead of only by manual/API calls.

## Event → template matrix

| Event | Template key | Dedupe policy |
|---|---|---|
| `TASK_ASSIGNED` (create with assignee, assign, reassign, claim) | `task-assigned` | transactional |
| `TASK_APPROVED` | `task-approved` | transactional |
| `TASK_REJECTED` | `task-rejected` | transactional |
| `TASK_COMPLETED` | `task-completed` | transactional |
| `TASK_ESCALATED` | `task-escalated` | periodic (time-boxed) |
| `WORKFLOW_STARTED` | `workflow-started` | transactional |
| `WORKFLOW_COMPLETED` | `workflow-completed` | transactional |
| `WORKFLOW_FAILED` | `workflow-failed` | transactional |
| `WORKFLOW_PAUSED` | `workflow-paused` | transactional |
| `WORKFLOW_RESUMED` | `workflow-resumed` | transactional |

An unmapped event uses the generic `notification` template and increments
`awap.notification.producer.template-fallback`, so a new event type is noisy rather than silent. The
matrix lives in `NotificationEventMatrix`; the built-in fallbacks are in
`NotificationTemplateRenderer`, and a tenant override of the same key/locale wins over both.

## How a request is produced

1. `TaskService` / `WorkflowEngineService` write the lifecycle fact through `OutboxEventPublisher`
   (`TASK`/`WORKFLOW` aggregate) **inside the caller's transaction** and fan it out in-process.
2. `NotificationEventProducer` (notification module) listens for `TaskLifecycleEvent` /
   `WorkflowLifecycleEvent` and resolves:
   - **recipient** — assignee, then the task creator (`created_by`, the task owner), then the tenant
     admin (`UserRepository.findFirstByTenantIdAndRole(…, TENANT_ADMIN)`);
   - **channel** — `awap.notification.default-channel` (default `EMAIL`; an unknown value falls back
     to `EMAIL` with a warning);
   - **locale** — `awap.notification.default-locale` (default `en`);
   - **template** — the matrix above.
3. The producer writes a `NOTIFICATION_REQUESTED` outbox row (same transaction) with
   `businessKey = <EVENT_TYPE>:<aggregateId>` and `eventType = <EVENT_TYPE>`. The relay maps that
   event type to the `notification.send` topic, and `NotificationRequestedEventListener` dispatches
   it; `NotificationService` hashes the business key into the dedupe key and creates exactly one
   delivery row per event type per aggregate (replays are suppressed inside the dedupe window and
   counted by `awap.notification.dedupe.suppressed`).
4. The delivery row **is** the inbox row (`notifications` reads `notification_deliveries`), so a
   produced notification is immediately visible to the tenant and invisible to every other tenant
   (RLS + tenant-scoped queries).

## Failure behaviour

The delivery transports themselves — SMTP, SMS gateway and push — plus their error classification,
provider ids and bounce suppression are documented in
[notification-providers.md](notification-providers.md).

- No recipient: logged, counted (`awap.notification.producer.missing-recipient`) and skipped. The
  task/workflow transaction still commits — a notification problem never fails the business action.
- Missing template override: the built-in English template renders; if the body cannot be rendered
  the delivery fails visibly (`TEMPLATE_RENDER_FAILED`) instead of being silently dropped.
- Duplicate Kafka record or repeated lifecycle event: the delivery row is the system of record and
  the repeat is suppressed, so the inbox keeps one row.

## Tests

- `NotificationEventMatrixTest` — every mapped event plus the fallback/normalisation.
- `NotificationEventProducerTest` — assignee → owner → admin fallback, missing recipient, generic
  template, configured channel/locale, invalid channel, serialization failure.
- `NotificationProducerPipelineTest` — producer payload → dispatch → one delivery row and one inbox
  row, tenant-scoped inbox, replay suppressed.
- `TaskLifecycleEventPublishingTest` / `WorkflowEngineServiceTest` — every task and workflow
  transition publishes the matching lifecycle fact.

## Adding a new event

1. Add the event type to `NotificationEventMatrix` and a built-in template to
   `NotificationTemplateRenderer`.
2. Publish the lifecycle fact from the owning service (outbox + in-process).
3. Extend `NotificationEventProducerTest` and the matrix test; the fallback counter makes a missed
   mapping visible in production.
