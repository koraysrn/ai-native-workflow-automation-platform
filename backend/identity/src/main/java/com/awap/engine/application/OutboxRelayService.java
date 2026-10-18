package com.awap.engine.application;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Relays unprocessed outbox events to Kafka; marks them published on success. */
@Service
public class OutboxRelayService {

  private final OutboxEventRepository outboxRepository;
  private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

  public OutboxRelayService(
      OutboxEventRepository outboxRepository,
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
    this.outboxRepository = outboxRepository;
    this.kafkaTemplateProvider = kafkaTemplateProvider;
  }

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void relayEvents() {
    KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    if (kafkaTemplate == null) {
      return;
    }
    List<OutboxEvent> events = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
    for (OutboxEvent event : events) {
      try {
        kafkaTemplate
            .send(
                topicFor(event.getEventType()),
                event.getAggregateId().toString(),
                event.getPayloadJson())
            .get(10, TimeUnit.SECONDS);
        event.markPublished();
      } catch (Exception ex) {
        // keep unpublished so the next sweep retries
      }
    }
  }

  /** Maps outbox event types to the spec'd Kafka topics. */
  private String topicFor(String eventType) {
    return switch (eventType) {
      case "TASK_COMPLETED", "TASK_ESCALATION_DUE" -> "workflow.task.events";
      case "FORM_SUBMITTED" -> "workflow.instance.events";
      default -> "workflow.instance.events";
    };
  }
}
