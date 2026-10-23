package com.awap.engine.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes dead-letter messages, records a metric and logs them for the alarm workflow. */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class DlqConsumer {

  private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

  private final MeterRegistry meterRegistry;

  public DlqConsumer(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @KafkaListener(topics = "${awap.kafka.dlq-topic:awap.dlq}", groupId = "awap-dlq")
  public void handle(String message) {
    meterRegistry.counter("awap.dlq.messages").increment();
    log.warn("Dead-letter message received: {}", message);
  }
}
