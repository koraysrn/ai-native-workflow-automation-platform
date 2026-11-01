package com.awap.usage.interfaces.event;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.usage.application.UsageMeteringService;
import com.awap.usage.domain.UsageEventKeys;
import com.awap.usage.domain.UsageMetric;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Meters usage from the analytics event pipeline.
 *
 * <p>The consumer derives the same deterministic idempotency key as the synchronous service hook
 * (workflow instance id / task id), so a Kafka redelivery that follows an already-counted HTTP
 * transaction is a no-op instead of a double increment.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class UsageEventListener {

  private static final String WORKFLOW_STARTED = "WORKFLOW_STARTED";
  private static final String TASK_COMPLETED = "TASK_COMPLETED";
  private static final String APPROVAL_COMPLETED = "APPROVAL_COMPLETED";

  private final ObjectMapper objectMapper;
  private final UsageMeteringService meteringService;
  private final KafkaTenantContext tenantContext;

  public UsageEventListener(
      ObjectMapper objectMapper,
      UsageMeteringService meteringService,
      KafkaTenantContext tenantContext) {
    this.objectMapper = objectMapper;
    this.meteringService = meteringService;
    this.tenantContext = tenantContext;
  }

  // A separate group from AnalyticsEventListener: both consume analytics.events and must each see
  // every record, rather than splitting the topic's partitions between them.
  @KafkaListener(topics = "analytics.events", groupId = "${awap.kafka.usage-group-id:awap-usage}")
  public void onMessage(String payload) {
    JsonNode event;
    try {
      event = objectMapper.readTree(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid AnalyticsEvent payload", ex);
    }

    UUID tenantId = UUID.fromString(event.path("tenantId").asText());
    Optional<MeteredEvent> metered = map(event);
    metered.ifPresent(
        increment ->
            tenantContext.run(
                tenantId,
                () ->
                    meteringService.record(
                        tenantId, increment.metric(), increment.delta(), increment.eventKey())));
  }

  private Optional<MeteredEvent> map(JsonNode event) {
    String eventType = event.path("eventType").asText("");
    if (WORKFLOW_STARTED.equals(eventType)) {
      return uuid(event, "workflowInstanceId")
          .map(
              instanceId ->
                  new MeteredEvent(
                      UsageMetric.WORKFLOW_EXECUTION,
                      1L,
                      UsageEventKeys.workflowStarted(instanceId)));
    }
    if (TASK_COMPLETED.equals(eventType) || APPROVAL_COMPLETED.equals(eventType)) {
      return uuid(event, "taskId")
          .map(
              taskId ->
                  new MeteredEvent(
                      UsageMetric.TASK_DECISION, 1L, UsageEventKeys.taskDecision(taskId)));
    }
    return Optional.empty();
  }

  private Optional<UUID> uuid(JsonNode event, String field) {
    JsonNode value = event.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(value.asText()));
  }

  /** One mapped usage increment. */
  record MeteredEvent(UsageMetric metric, long delta, String eventKey) {}
}
