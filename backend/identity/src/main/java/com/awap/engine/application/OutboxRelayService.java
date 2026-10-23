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

/** Relays unprocessed outbox events to Kafka exactly once, even under concurrent relay runs. */
@Service
public class OutboxRelayService {

  private static final long SEND_TIMEOUT_SECONDS = 10;

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
    List<OutboxEvent> events = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
    for (OutboxEvent event : events) {
      // Claim the event atomically. The scheduled task and any other relay instance must never
      // publish the same event twice.
      if (outboxRepository.claim(event.getId()) == 0) {
        continue;
      }
      try {
        kafkaTemplate
            .send(
                topicFor(event.getEventType()),
                event.getAggregateId().toString(),
                event.getPayloadJson())
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // The claim (published=true) is committed with the surrounding transaction.
      } catch (Exception ex) {
        // Release the claim so the next sweep retries the event.
        outboxRepository.release(event.getId());
      }
    }
  }

  /** Maps outbox event types to the spec'd Kafka topics. */
  private String topicFor(String eventType) {
    return switch (eventType) {
      case "TASK_COMPLETED", "TASK_ESCALATION_DUE" -> "workflow.task.events";
      case "FORM_SUBMITTED" -> "workflow.instance.events";
      case "organization.company.created",
          "organization.department.created",
          "organization.team.created",
          "organization.position.created",
          "organization.user.created",
          "organization.team.user_assigned" ->
          "organization.events";
      default -> "workflow.instance.events";
    };
  }
}
