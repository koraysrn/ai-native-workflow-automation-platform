package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.event.AnalyticsEvent;
import com.awap.engine.event.DocumentEvent;
import com.awap.engine.event.NotificationRequestedEvent;
import com.awap.engine.event.OrganizationChangeEvent;
import com.awap.engine.event.TaskCompletedEvent;
import com.awap.engine.event.WorkflowInstanceStateEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.junit.jupiter.api.Test;

/**
 * A pinned-BACKWARD Schema Registry contract: each producer schema is registered as a new version
 * under its subject and must remain readable by every previously registered version. The registry
 * is in-memory because no external Schema Registry service runs in this repository; when one is
 * provisioned it can be wired through {@code SCHEMA_REGISTRY_URL} without changing these checks.
 */
class AvroSchemaRegistryCompatibilityTest {

  private static final List<Schema> PRODUCER_SCHEMAS =
      List.of(
          AnalyticsEvent.getClassSchema(),
          DocumentEvent.getClassSchema(),
          NotificationRequestedEvent.getClassSchema(),
          OrganizationChangeEvent.getClassSchema(),
          TaskCompletedEvent.getClassSchema(),
          WorkflowInstanceStateEvent.getClassSchema());

  @Test
  void everyProducerSchemaRegistersBackwardCompatibleWithAllPriorVersions() {
    Map<String, List<Schema>> registry = new HashMap<>();

    for (Schema current : PRODUCER_SCHEMAS) {
      String subject = current.getFullName();
      List<Schema> versions = registry.computeIfAbsent(subject, key -> new ArrayList<>());

      // Register the immediately preceding version first, then assert BACKWARD compatibility.
      Schema previous = previousVersionOf(current);
      for (Schema registered : versions) {
        assertThat(compatibility(registered, current))
            .as("subject %s: version %s must stay BACKWARD compatible", subject, registered)
            .isEqualTo(SchemaCompatibilityType.COMPATIBLE);
      }
      versions.add(current);
      versions.add(previous);
    }
  }

  @Test
  void everyProducerSchemaIsBackwardCompatibleWithItsPreviousVersion() {
    for (Schema current : PRODUCER_SCHEMAS) {
      Schema previous = previousVersionOf(current);
      assertThat(compatibility(previous, current))
          .as(
              "schema %s must be BACKWARD compatible with its previous version",
              current.getFullName())
          .isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }
  }

  @Test
  void changingAFieldTypeBreaksBackwardCompatibility() {
    Schema original = TaskCompletedEvent.getClassSchema();
    Schema broken = withFieldType(original, "tenantId", Schema.create(Schema.Type.INT));

    assertThat(compatibility(original, broken)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
  }

  private SchemaCompatibilityType compatibility(Schema reader, Schema writer) {
    return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType();
  }

  /** Builds the immediately preceding version by dropping the last field (the one evolved away). */
  private Schema previousVersionOf(Schema current) {
    List<Schema.Field> fields =
        current.getFields().stream()
            .limit(Math.max(current.getFields().size() - 1, 1))
            .map(this::copyField)
            .toList();
    return Schema.createRecord(
        current.getName(), current.getDoc(), current.getNamespace(), current.isError(), fields);
  }

  /** Replaces one field's type with a new schema, keeping all other fields intact. */
  private Schema withFieldType(Schema schema, String fieldName, Schema newType) {
    List<Schema.Field> fields =
        schema.getFields().stream()
            .map(
                field ->
                    field.name().equals(fieldName)
                        ? new Schema.Field(field.name(), newType, field.doc(), null)
                        : copyField(field))
            .toList();
    return Schema.createRecord(
        schema.getName(), schema.getDoc(), schema.getNamespace(), schema.isError(), fields);
  }

  private Schema.Field copyField(Schema.Field field) {
    return new Schema.Field(field.name(), field.schema(), field.doc(), field.defaultVal());
  }
}
