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

## Operations

- The Schema Registry must be reachable at startup; a registry outage blocks producing/consuming
  and is surfaced via the consumer error handler rather than silently dropping events.
- Duplicate registration of the same schema is idempotent.
