package com.awap.engine.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/**
 * Binary Avro serializer/deserializer for generated {@link SpecificRecord} event classes.
 *
 * <p>The KafkaAvroSerializer/Deserializer + Schema Registry layer is wired on top of this in the
 * event pipeline build step; this serde is the schema-driven core used by that layer.
 */
public class AvroEventSerde {

  public <T extends SpecificRecord> byte[] serialize(T record) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    SpecificDatumWriter<T> writer = new SpecificDatumWriter<>(record.getSchema());
    try {
      writer.write(record, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Avro serialization failed.", ex);
    }
  }

  public <T extends SpecificRecord> T deserialize(byte[] data, Class<T> type) {
    try {
      T record = type.getDeclaredConstructor().newInstance();
      BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
      SpecificDatumReader<T> reader = new SpecificDatumReader<>(record.getSchema());
      return reader.read(null, decoder);
    } catch (ReflectiveOperationException | IOException ex) {
      throw new IllegalStateException("Avro deserialization failed.", ex);
    }
  }
}
