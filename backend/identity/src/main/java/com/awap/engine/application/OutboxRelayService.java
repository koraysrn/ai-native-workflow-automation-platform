package com.awap.engine.application;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays unprocessed outbox events to Kafka.
 *
 * <p>The read/release behaviour lives behind {@link OutboxRelaySource}, so the dedicated-pool path
 * ({@link OutboxRelayMode#JDBC}, {@code SECURITY DEFINER} functions) and the JPA fallback ({@link
 * OutboxRelayMode#JPA}) run through exactly the same loop, the same failure accounting and the same
 * metrics (issue #122). Which adapter is injected is decided by {@code
 * awap.outbox.datasource.enabled}; the fallback is restricted to dev/test by {@link
 * OutboxRelayConfigurationGuard}.
 *
 * <p>Every run records its outcome in {@link OutboxRelayStatus} so the {@code outboxRelay} health
 * indicator turns {@code DOWN} when claiming or publishing fails instead of the failure being
 * visible only in the logs.
 */
@Service
public class OutboxRelayService {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final OutboxRelaySource source;
  private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
  private final OutboxRelayStatus status;
  private final OutboxRelayMetrics metrics;
  private final int batchSize;

  @Autowired
  public OutboxRelayService(
      OutboxRelaySource source,
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
      OutboxRelayStatus status,
      OutboxRelayMetrics metrics,
      @Value("${awap.outbox.batch-size:100}") int batchSize) {
    this.source = source;
    this.kafkaTemplateProvider = kafkaTemplateProvider;
    this.status = status;
    this.metrics = metrics;
    this.batchSize = Math.max(1, batchSize);
  }

  /**
   * The delay is configurable so a test can push the schedule out of the way; production keeps the
   * five second default.
   */
  @Scheduled(
      fixedDelayString = "${awap.outbox.relay-interval-ms:5000}",
      initialDelayString = "${awap.outbox.relay-initial-delay-ms:5000}")
  @Transactional
  public void relayEvents() {
    KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    if (kafkaTemplate == null) {
      return;
    }
    final List<OutboxRelaySource.PendingEvent> batch;
    try {
      batch = source.claimBatch(batchSize);
    } catch (RuntimeException ex) {
      metrics.claimFailure();
      status.recordFailure(OutboxRelayMetrics.REASON_CLAIM_FAILED, 0);
      log.error(
          "Failed to claim an outbox batch with the {} relay path; the next run will retry",
          source.mode().tag(),
          ex);
      return;
    }
    int published = 0;
    boolean failed = false;
    for (OutboxRelaySource.PendingEvent event : batch) {
      if (send(kafkaTemplate, event)) {
        published++;
        continue;
      }
      source.release(event.id());
      metrics.publishFailure();
      failed = true;
      log.warn("Kafka send failed for outbox event {}; the claim was released", event.id());
    }
    if (failed) {
      status.recordFailure(OutboxRelayMetrics.REASON_SEND_FAILED, published);
    } else {
      status.recordSuccess(published);
    }
  }

  private boolean send(
      KafkaTemplate<String, String> kafkaTemplate, OutboxRelaySource.PendingEvent event) {
    try {
      kafkaTemplate
          .send(topicFor(event.eventType()), event.aggregateId().toString(), event.payloadJson())
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
