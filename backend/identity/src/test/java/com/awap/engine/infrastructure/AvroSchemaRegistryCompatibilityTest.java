package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.event.AnalyticsEvent;
import com.awap.engine.event.DocumentEvent;
import com.awap.engine.event.NotificationRequestedEvent;
import com.awap.engine.event.OrganizationChangeEvent;
import com.awap.engine.event.TaskCompletedEvent;
import com.awap.engine.event.WorkflowInstanceStateEvent;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.junit.jupiter.api.Test;

/**
 * Simulates a Schema Registry pinned to BACKWARD compatibility: every producer schema must still be
 * readable by the previous version it replaces, and a breaking field-type change is rejected.
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
  void everyProducerSchemaIsBackwardCompatibleWithItsPreviousVersion() {
    for (Schema current : PRODUCER_SCHEMAS) {
      Schema previous = previousVersionOf(current);
      SchemaCompatibility.SchemaPairCompatibility result =
          SchemaCompatibility.checkReaderWriterCompatibility(previous, current);
      assertThat(result.getType())
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

    SchemaCompatibility.SchemaPairCompatibility result =
        SchemaCompatibility.checkReaderWriterCompatibility(original, broken);

    assertThat(result.getType()).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
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
