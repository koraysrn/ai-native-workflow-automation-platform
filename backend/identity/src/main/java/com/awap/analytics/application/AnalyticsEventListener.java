package com.awap.analytics.application;

import com.awap.analytics.domain.AnalyticsEventType;
import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code AnalyticsEvent} records and forwards them to the time-series sink. */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class AnalyticsEventListener {

  private final ObjectMapper objectMapper;
  private final AnalyticsService analyticsService;
  private final KafkaTenantContext tenantContext;

  public AnalyticsEventListener(
      ObjectMapper objectMapper,
      AnalyticsService analyticsService,
      KafkaTenantContext tenantContext) {
    this.objectMapper = objectMapper;
    this.analyticsService = analyticsService;
    this.tenantContext = tenantContext;
  }

  @KafkaListener(
      topics = "analytics.events",
      groupId = "${awap.kafka.analytics-group-id:awap-analytics}")
  public void onMessage(String payload) {
    try {
      JsonNode event = objectMapper.readTree(payload);
      UUID tenantId = UUID.fromString(event.path("tenantId").asText());
      AnalyticsEventType type = AnalyticsEventType.valueOf(event.path("eventType").asText());
      UUID workflowInstanceId =
          event.path("workflowInstanceId").isNull()
              ? null
              : UUID.fromString(event.path("workflowInstanceId").asText());
      UUID taskId =
          event.path("taskId").isNull() ? null : UUID.fromString(event.path("taskId").asText());
      long durationMs = event.path("durationMs").isNull() ? 0L : event.path("durationMs").asLong();

      tenantContext.run(
          tenantId,
          () -> analyticsService.record(tenantId, type, workflowInstanceId, taskId, durationMs));
    } catch (Exception ex) {
      // Throwing hands the malformed record to the Kafka error handler and then the per-topic DLQ.
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid AnalyticsEvent payload: " + ex.getMessage());
    }
  }
}
