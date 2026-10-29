package com.awap.engine.infrastructure;

import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Idempotent dead-letter handler.
 *
 * <p>It only records metrics and writes quarantined payloads to a local file. It deliberately has
 * no Kafka producer dependency, so a DLQ record can never be re-published to its source topic (or
 * bounce between {@code *.dlq} topics). Malformed records are quarantined instead of thrown, which
 * keeps the listener from retrying forever and from being routed to {@code *.dlq.dlq}.
 */
@Component
public class DlqMessageHandler {

  private static final Logger log = LoggerFactory.getLogger(DlqMessageHandler.class);

  private final DlqMetrics metrics;
  private final DlqMessageInspector inspector;
  private final DlqQuarantineStore quarantineStore;
  private final DlqMessageDeduplicator deduplicator;

  public DlqMessageHandler(
      DlqMetrics metrics,
      DlqMessageInspector inspector,
      DlqQuarantineStore quarantineStore,
      DlqMessageDeduplicator deduplicator) {
    this.metrics = metrics;
    this.inspector = inspector;
    this.quarantineStore = quarantineStore;
    this.deduplicator = deduplicator;
  }

  public void handle(ConsumerRecord<String, String> record) {
    String topic = record.topic();
    if (!deduplicator.firstDelivery(topic, record.partition(), record.offset())) {
      metrics.recordDuplicate(topic);
      log.debug(
          "Ignoring redelivered dead-letter record topic={} partition={} offset={}",
          topic,
          record.partition(),
          record.offset());
      return;
    }

    metrics.recordReceived(topic);

    Optional<String> malformedReason = inspector.malformedReason(record.value());
    if (malformedReason.isPresent()) {
      quarantine(topic, record, malformedReason.get());
      metrics.recordQuarantined(topic);
      return;
    }

    log.warn(
        "Dead-letter message received topic={} partition={} offset={} payloadBytes={}",
        topic,
        record.partition(),
        record.offset(),
        record.value() == null ? 0 : record.value().length());
  }

  private void quarantine(String topic, ConsumerRecord<String, String> record, String reason) {
    try {
      quarantineStore.store(topic, record.partition(), record.offset(), record.value(), reason);
      log.warn(
          "Quarantined malformed dead-letter message topic={} partition={} offset={} reason={}",
          topic,
          record.partition(),
          record.offset(),
          reason);
    } catch (RuntimeException ex) {
      // Never rethrow: the error handler would publish the poison record to another DLQ topic.
      log.error(
          "Failed to quarantine malformed dead-letter message topic={} partition={} offset={}",
          topic,
          record.partition(),
          record.offset(),
          ex);
    }
  }
}
