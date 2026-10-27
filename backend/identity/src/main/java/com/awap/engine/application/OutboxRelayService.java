package com.awap.engine.application;

import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import com.awap.engine.infrastructure.OutboxRelayJdbcRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
  private final ObjectProvider<OutboxRelayJdbcRepository> jdbcRepositoryProvider;
  private final int batchSize;

  /** Test/backward-compatible constructor using the shared JPA repository. */
  OutboxRelayService(
      OutboxEventRepository outboxRepository,
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
    this(outboxRepository, kafkaTemplateProvider, null, 100);
  }

  @Autowired
  public OutboxRelayService(
      OutboxEventRepository outboxRepository,
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
      ObjectProvider<OutboxRelayJdbcRepository> jdbcRepositoryProvider,
      @Value("${awap.outbox.batch-size:100}") int batchSize) {
    this.outboxRepository = outboxRepository;
    this.kafkaTemplateProvider = kafkaTemplateProvider;
    this.jdbcRepositoryProvider = jdbcRepositoryProvider;
    this.batchSize = Math.max(1, batchSize);
  }

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void relayEvents() {
    KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    if (kafkaTemplate == null) {
      return;
    }
    OutboxRelayJdbcRepository jdbcRepository =
        jdbcRepositoryProvider == null ? null : jdbcRepositoryProvider.getIfAvailable();
    if (jdbcRepository != null) {
      relayWithDedicatedPool(jdbcRepository, kafkaTemplate);
    } else {
      relayWithJpa(kafkaTemplate);
    }
  }

  private void relayWithDedicatedPool(
      OutboxRelayJdbcRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
    for (OutboxRelayJdbcRepository.RelayEvent event : repository.claimBatch(batchSize)) {
      if (!send(kafkaTemplate, event.eventType(), event.aggregateId(), event.payloadJson())) {
        repository.release(event.id());
      }
    }
  }

  private void relayWithJpa(KafkaTemplate<String, String> kafkaTemplate) {
    List<OutboxEvent> events = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
    for (OutboxEvent event : events) {
      if (outboxRepository.claim(event.getId()) == 0) {
        continue;
      }
      if (!send(
          kafkaTemplate, event.getEventType(), event.getAggregateId(), event.getPayloadJson())) {
        outboxRepository.release(event.getId());
      }
    }
  }

  private boolean send(
      KafkaTemplate<String, String> kafkaTemplate,
      String eventType,
      java.util.UUID aggregateId,
      String payloadJson) {
    try {
      kafkaTemplate
          .send(topicFor(eventType), aggregateId.toString(), payloadJson)
          .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  /** Maps outbox event types to the spec'd Kafka topics. */
  private String topicFor(String eventType) {
    return switch (eventType) {
      case "TASK_COMPLETED", "TASK_ESCALATION_DUE" -> "workflow.task.events";
      case "FORM_SUBMITTED" -> "workflow.instance.events";
      case "SUB_PROCESS_COMPLETED" -> "workflow.subprocess.events";
      case "WEBHOOK_DELIVERY_DEAD" -> "webhook.delivery.dlq";
      case "ANALYTICS_EVENT" -> "analytics.events";
      case "NOTIFICATION_REQUESTED" -> "notification.send";
      case "DOCUMENT_REGISTERED",
          "DOCUMENT_VERSION_CREATED",
          "DOCUMENT_ARCHIVED",
          "DOCUMENT_DELETED" ->
          "document.events";
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
