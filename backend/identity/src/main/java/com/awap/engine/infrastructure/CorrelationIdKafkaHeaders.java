package com.awap.engine.infrastructure;

import com.awap.common.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Adds the correlation-ID header to a Kafka record (issue #191). */
public final class CorrelationIdKafkaHeaders {

  private CorrelationIdKafkaHeaders() {}

  /**
   * Returns the record with {@code X-Correlation-Id} added when the value is present. A null/blank
   * value leaves the record unchanged; the outbox migration backfills legacy rows, and the consumer
   * generates an ID when the header is missing.
   */
  public static ProducerRecord<String, String> withCorrelationId(
      ProducerRecord<String, String> record, String correlationId) {
    if (correlationId != null && !correlationId.isBlank()) {
      record.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }
}
