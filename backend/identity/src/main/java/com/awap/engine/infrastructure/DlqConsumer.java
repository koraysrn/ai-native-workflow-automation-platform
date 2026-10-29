package com.awap.engine.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dual-consumes dead-letter traffic (issue #123).
 *
 * <p>The subscription pattern is computed by {@link DlqTopicSubscription}: it always covers every
 * {@code <source>.dlq} topic and, while {@code awap.kafka.legacy-dlq-enabled} is true, the legacy
 * {@code awap.dlq} topic as well. The pattern is anchored, so unrelated topics such as {@code
 * not-a-dlq-topic} are never routed here.
 *
 * <p>This listener has no producer dependency: it delegates to {@link DlqMessageHandler}, which
 * only meters and quarantines records, never re-publishing them to Kafka.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class DlqConsumer {

  private final DlqMessageHandler handler;

  public DlqConsumer(DlqMessageHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(
      topicPattern = "#{@dlqTopicSubscription.topicPattern()}",
      groupId = "${awap.kafka.dlq-group-id:awap-dlq}")
  public void onMessage(ConsumerRecord<String, String> record) {
    handler.handle(record);
  }
}
