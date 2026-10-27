package com.awap.engine.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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

/**
 * Relays unprocessed outbox events to Kafka.
 *
 * <p>The relay no longer flips {@code published} while claiming. It takes a lease (claim token +
 * {@code claimed_at}) and only marks the row published after the broker acknowledged the send. A
 * crash between claim and publish therefore leaves an unpublished row with a stale lease that
 * {@link OutboxReconciliationScheduler} re-queues instead of silently losing the event.
 *
 * <p>The Kafka producer is idempotent ({@code enable.idempotence=true}, {@code acks=all}) and the
 * record key is the aggregate id, so a republish after a crash is deduplicated per aggregate.
 */
@Service
public class OutboxRelayService {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

  /** Upper bound for a single broker send; the configured lease must be longer than this. */
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
  private final OutboxClaimStore claimStore;
  private final ObjectMapper objectMapper;
  private final OutboxMetrics metrics;
  private final int batchSize;
  private final Duration claimLease;
  private final int maxPublishAttempts;

  @Autowired
  public OutboxRelayService(
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
      OutboxClaimStore claimStore,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${awap.outbox.batch-size:100}") int batchSize,
      @Value("${awap.outbox.claim-lease:60s}") Duration claimLease,
      @Value("${awap.outbox.max-publish-attempts:5}") int maxPublishAttempts) {
    this(
        kafkaTemplateProvider,
        claimStore,
        objectMapper,
        new OutboxMetrics(meterRegistry),
        batchSize,
        claimLease,
        maxPublishAttempts);
  }

  /** Test-friendly constructor that reuses an already-built metrics facade. */
  public OutboxRelayService(
      ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
      OutboxClaimStore claimStore,
      ObjectMapper objectMapper,
      OutboxMetrics metrics,
      int batchSize,
      Duration claimLease,
      int maxPublishAttempts) {
    if (claimLease.compareTo(SEND_TIMEOUT) <= 0) {
      throw new IllegalStateException(
          "awap.outbox.claim-lease ("
              + claimLease
              + ") must be longer than the Kafka send timeout ("
              + SEND_TIMEOUT
              + "); otherwise a second relay may publish a row that is still being sent");
    }
    this.kafkaTemplateProvider = kafkaTemplateProvider;
    this.claimStore = claimStore;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.batchSize = Math.max(1, batchSize);
    this.claimLease = claimLease;
    this.maxPublishAttempts = Math.max(1, maxPublishAttempts);
  }

  /**
   * Starting the relay a few seconds after boot keeps startup (Flyway, Hibernate validation) from
   * competing with outbox polling, and gives the reconciliation scheduler the first word on stale
   * leases left behind by a previous instance.
   */
  @Scheduled(
      fixedDelayString = "${awap.outbox.relay-interval-ms:5000}",
      initialDelayString = "${awap.outbox.relay-initial-delay-ms:5000}")
  public void relayEvents() {
    KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    if (kafkaTemplate == null) {
      return;
    }
    final List<OutboxClaimStore.ClaimedOutboxEvent> claimed;
    try {
      claimed = claimStore.claimBatch(batchSize, claimLease, maxPublishAttempts);
    } catch (RuntimeException ex) {
      log.error("Failed to claim an outbox batch; the next run will retry", ex);
      return;
    }
    for (OutboxClaimStore.ClaimedOutboxEvent event : claimed) {
      relayOne(kafkaTemplate, event);
    }
  }

  private void relayOne(
      KafkaTemplate<String, String> kafkaTemplate, OutboxClaimStore.ClaimedOutboxEvent event) {
    if (!hasValidPayload(event.payloadJson())) {
      // A poison payload would fail forever; releasing counts the attempt and the claim function
      // stops leasing the row once maxPublishAttempts is reached.
      metrics.publishFailure(OutboxMetrics.REASON_CORRUPTED_PAYLOAD);
      claimStore.release(event.id(), event.claimToken());
      log.error(
          "Outbox event {} has a corrupted payload and was released without publishing",
          event.id());
      return;
    }
    if (send(kafkaTemplate, event)) {
      if (!claimStore.markPublished(event.id(), event.claimToken())) {
        // The lease expired while the send was in flight and another relay owns the row now.
        metrics.publishFailure(OutboxMetrics.REASON_CLAIM_LOST);
        log.warn(
            "Lost the outbox claim for event {} before it could be marked published", event.id());
      }
      return;
    }
    metrics.publishFailure(OutboxMetrics.REASON_SEND_FAILED);
    claimStore.release(event.id(), event.claimToken());
    log.warn("Kafka send failed for outbox event {}; the claim was released", event.id());
  }

  private boolean hasValidPayload(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return false;
    }
    try {
      return objectMapper.readTree(payloadJson) != null;
    } catch (Exception ex) {
      return false;
    }
  }

  private boolean send(
      KafkaTemplate<String, String> kafkaTemplate, OutboxClaimStore.ClaimedOutboxEvent event) {
    try {
      kafkaTemplate
          .send(topicFor(event.eventType()), event.aggregateId().toString(), event.payloadJson())
          .get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
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
