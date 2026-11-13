# Event Schema Evolution Policy

The backend event pipeline uses Avro schemas registered in the Kafka Schema Registry.

## Schemas

Schemas live under `backend/identity/src/main/avro` and are the single source of truth:

- `TaskCompletedEvent.avsc`
- `WorkflowInstanceStateEvent.avsc`
- `OrganizationChangeEvent.avsc`

Java bindings are generated at build time by `avro-maven-plugin` (phase `generate-sources`).

## Compatibility

- The registry enforces **BACKWARD** compatibility: a new schema version must be readable by
  consumers using the previous version.
- Removing a field or changing a field type is a breaking change and is rejected by the registry.
- Additive, optional fields (with defaults) are the only safe evolution.

## Serialization

- Producers serialize with `KafkaAvroSerializer`; consumers deserialize with
  `KafkaAvroDeserializer`.
- `schema.registry.url` is provided via `awap.kafka.schema-registry-url`.
- Serialization failures are routed to the DLQ by the consumer error handler.

## Topic contracts

Outbox event types map to topics in `OutboxRelayService#topicFor`. The mapping is contract-tested:
a type that lands on the wrong topic is either dead-lettered as a poison record or silently
ignored.

| Event type | Topic | Consumer |
|---|---|---|
| `TASK_COMPLETED` | `workflow.task.events` | `NodeCompletedEventListener` |
| `TASK_ESCALATION_DUE` | `task.escalation.events` | `TaskEscalationEventListener` (issue #207) |
| `FORM_SUBMITTED`, `WORKFLOW_INSTANCE_STATE` | `workflow.instance.events` | `NodeCompletedEventListener` |
| `SUB_PROCESS_COMPLETED` | `workflow.subprocess.events` | `SubProcessCompletedEventListener` |
| `NOTIFICATION_REQUESTED` | `notification.send` | `NotificationRequestedEventListener` |
| `DOCUMENT_*` | `document.events` | `DocumentEventListener` |
| `ANALYTICS_EVENT` | `analytics.events` | `AnalyticsEventListener`, `UsageEventListener` |

`TaskEscalationDue` carries `tenantId`, `taskId` and the configured escalation `assigneeId`. It
must never be published on `workflow.task.events`: that listener deserializes every record as a
`NodeCompletedEvent`, which caused retries and DLQ noise before issue #207. Every consumed topic has
a matching `<topic>.dlq` provisioned by `infra/docker-compose.yml` (`kafka-init`).

### Document event contract (issue #208)

Every `DOCUMENT_REGISTERED`, `DOCUMENT_VERSION_CREATED`, `DOCUMENT_ARCHIVED` and
`DOCUMENT_DELETED` record is published on `document.events` with this JSON shape:

```json
{
  "tenantId": "uuid",
  "eventType": "DOCUMENT_VERSION_CREATED",
  "fileId": "uuid",
  "version": 2,
  "size": 2048,
  "format": "pdf",
  "objectKey": "<fileId>/v2"
}
```

The consumer (`DocumentEventListener`) restores the tenant context from `tenantId`, verifies that
the file belongs to that tenant (`file_records.tenant_id`) and then applies the fact through
`DocumentService.applyEvent`:

- `DOCUMENT_REGISTERED` / `DOCUMENT_VERSION_CREATED` create the `(file_id, version)` row only when it
  does not exist; a duplicate delivery is counted as `outcome=duplicate` and writes nothing.
- `DOCUMENT_ARCHIVED` / `DOCUMENT_DELETED` re-apply the terminal state of an existing row; a missing
  version row is counted as `outcome=missing_version` and does not crash the consumer.
- The consumer never republishes, so a `DOCUMENT_VERSION_CREATED` record cannot create another
  version (the old self-triggered loop).
- A foreign tenant, unknown file, malformed JSON or missing required field is rejected; parse
  failures continue to `document.events.dlq` through the shared error handler and increment
  `awap.document.event.failures`.

## Operations

- The Schema Registry must be reachable at startup; a registry outage blocks producing/consuming
  and is surfaced via the consumer error handler rather than silently dropping events.
- Duplicate registration of the same schema is idempotent.
