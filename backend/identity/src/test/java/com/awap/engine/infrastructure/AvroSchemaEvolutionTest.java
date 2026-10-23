package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.event.TaskCompletedEvent;
import java.io.ByteArrayOutputStream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

/**
 * Proves BACKWARD and FORWARD compatibility between an older event schema and the current generated
 * {@link TaskCompletedEvent} class, so producers/consumers can be upgraded independently.
 */
class AvroSchemaEvolutionTest {

  /** Older schema: the same record without the {@code outputJson} field added later. */
  private static final Schema OLD_SCHEMA =
      new Schema.Parser()
          .parse(
              """
              {"type":"record","name":"TaskCompletedEvent","namespace":"com.awap.engine.event",
               "fields":[
                 {"name":"tenantId","type":"string"},
                 {"name":"workflowInstanceId","type":"string"},
                 {"name":"nodeExecutionId","type":"string"}]}
              """);

  @Test
  void forwardCompatibilityReadsOldRecordWithNewReader() throws Exception {
    GenericRecord oldRecord = new GenericData.Record(OLD_SCHEMA);
    oldRecord.put("tenantId", "t1");
    oldRecord.put("workflowInstanceId", "w1");
    oldRecord.put("nodeExecutionId", "n1");

    byte[] bytes = serialize(OLD_SCHEMA, oldRecord);

    SpecificDatumReader<TaskCompletedEvent> reader =
        new SpecificDatumReader<>(OLD_SCHEMA, TaskCompletedEvent.getClassSchema());
    TaskCompletedEvent event = read(bytes, reader);

    assertThat(event.getTenantId().toString()).isEqualTo("t1");
    assertThat(event.getWorkflowInstanceId().toString()).isEqualTo("w1");
    assertThat(event.getNodeExecutionId().toString()).isEqualTo("n1");
    // The field added after the old schema was written defaults to null.
    assertThat(event.getOutputJson()).isNull();
  }

  @Test
  void backwardCompatibilityReadsNewRecordWithOldReader() throws Exception {
    TaskCompletedEvent newEvent =
        TaskCompletedEvent.newBuilder()
            .setTenantId("t1")
            .setWorkflowInstanceId("w1")
            .setNodeExecutionId("n1")
            .setOutputJson("{\"ok\":true}")
            .build();

    SpecificDatumWriter<TaskCompletedEvent> writer =
        new SpecificDatumWriter<>(TaskCompletedEvent.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    writer.write(newEvent, encoder);
    encoder.flush();

    GenericDatumReader<GenericRecord> reader =
        new GenericDatumReader<>(TaskCompletedEvent.getClassSchema(), OLD_SCHEMA);
    BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
    GenericRecord oldRecord = reader.read(null, decoder);

    assertThat(oldRecord.get("tenantId").toString()).isEqualTo("t1");
    assertThat(oldRecord.get("workflowInstanceId").toString()).isEqualTo("w1");
    // The reader schema has no outputJson field, so the extra field is dropped.
    assertThat(oldRecord.getSchema().getField("outputJson")).isNull();
  }

  private byte[] serialize(Schema schema, GenericRecord record) throws Exception {
    GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    writer.write(record, encoder);
    encoder.flush();
    return out.toByteArray();
  }

  private TaskCompletedEvent read(byte[] bytes, SpecificDatumReader<TaskCompletedEvent> reader)
      throws Exception {
    BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
    return reader.read(null, decoder);
  }
}
