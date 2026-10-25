package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code NotificationRequestedEvent} records from Kafka and dispatches them. A malformed
 * payload is rejected cleanly (thrown) so the error handler routes it to the DLQ without crashing
 * the consumer.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class NotificationRequestedEventListener {

  private final ObjectMapper objectMapper;
  private final NotificationService notificationService;

  public NotificationRequestedEventListener(
      ObjectMapper objectMapper, NotificationService notificationService) {
    this.objectMapper = objectMapper;
    this.notificationService = notificationService;
  }

  @KafkaListener(topics = "notification.requested.events")
  public void onMessage(String payload) {
    try {
      JsonNode event = objectMapper.readTree(payload);
      UUID tenantId = UUID.fromString(event.path("tenantId").asText());
      String recipient = event.path("recipient").asText();
      NotificationChannel channel = NotificationChannel.valueOf(event.path("channel").asText());
      String templateKey = event.path("templateKey").asText();
      String variablesJson =
          event.path("variablesJson").isNull() ? null : event.path("variablesJson").asText();

      notificationService.send(tenantId, recipient, channel, templateKey, variablesJson);
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid NotificationRequestedEvent payload: " + ex.getMessage());
    }
  }
}
