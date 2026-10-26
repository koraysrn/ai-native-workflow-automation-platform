package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code notification.send} (with the legacy {@code notification.requested.events} topic
 * still accepted) and dispatches each message. The delivery row makes the consumer idempotent, so a
 * redelivered Kafka record cannot send a second notification.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class NotificationRequestedEventListener {

  private final ObjectMapper objectMapper;
  private final NotificationService notificationService;
  private final KafkaTenantContext tenantContext;

  public NotificationRequestedEventListener(
      ObjectMapper objectMapper,
      NotificationService notificationService,
      KafkaTenantContext tenantContext) {
    this.objectMapper = objectMapper;
    this.notificationService = notificationService;
    this.tenantContext = tenantContext;
  }

  @KafkaListener(topics = {"notification.send", "notification.requested.events"})
  public void onMessage(String payload) {
    try {
      JsonNode event = objectMapper.readTree(payload);
      UUID tenantId = UUID.fromString(event.path("tenantId").asText());
      String recipient = event.path("recipient").asText();
      NotificationChannel channel = NotificationChannel.valueOf(event.path("channel").asText());
      String templateKey = event.path("templateKey").asText();
      String locale = event.path("locale").isNull() ? "en" : event.path("locale").asText("en");
      String variablesJson =
          event.path("variablesJson").isNull() ? null : event.path("variablesJson").asText();
      String dedupeKey = event.path("dedupeKey").isNull() ? null : event.path("dedupeKey").asText();

      tenantContext.run(
          tenantId,
          () ->
              notificationService.send(
                  tenantId, recipient, channel, templateKey, locale, variablesJson, dedupeKey));
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid NotificationRequestedEvent payload: " + ex.getMessage());
    }
  }
}
