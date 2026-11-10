package com.awap.engine.infrastructure;

import com.awap.common.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Restores the correlation ID from the Kafka record header for the listener invocation and clears
 * it afterwards (issue #191).
 *
 * <p>A missing or unsafe header produces a new ID rather than a null MDC value, and the MDC entry
 * is removed even when the listener fails, so the consumer thread cannot leak it into the next
 * record.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<String, String> {

  @Override
  public ConsumerRecord<String, String> intercept(
      ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
    Header header = record.headers().lastHeader(CorrelationId.HEADER);
    String candidate = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    CorrelationId.set(CorrelationId.resolve(candidate));
    return record;
  }

  @Override
  public void afterRecord(
      ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
    CorrelationId.clear();
  }

  @Override
  public void failure(
      ConsumerRecord<String, String> record,
      Exception exception,
      Consumer<String, String> consumer) {
    CorrelationId.clear();
  }
}
