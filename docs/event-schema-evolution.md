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

## Operations

- The Schema Registry must be reachable at startup; a registry outage blocks producing/consuming
  and is surfaced via the consumer error handler rather than silently dropping events.
- Duplicate registration of the same schema is idempotent.
