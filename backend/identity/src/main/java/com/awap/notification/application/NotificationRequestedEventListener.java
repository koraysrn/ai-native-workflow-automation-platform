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
      String dedupeKey = optionalText(event, "dedupeKey");
      String eventType = optionalText(event, "eventType");
      String businessKey = resolveBusinessKey(event);

      tenantContext.run(
          tenantId,
          () ->
              notificationService.send(
                  tenantId,
                  recipient,
                  channel,
                  templateKey,
                  locale,
                  variablesJson,
                  dedupeKey,
                  businessKey,
                  eventType));
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid NotificationRequestedEvent payload: " + ex.getMessage());
    }
  }

  /**
   * Resolves the business identity of the notification. Workflow instance, task, approval and form
   * producers put their aggregate id on the event; an explicit {@code businessKey} always wins so a
   * producer can supply a composite identity when one record emits several notifications.
   */
  static String resolveBusinessKey(JsonNode event) {
    String explicit = optionalText(event, "businessKey");
    if (explicit != null) {
      return explicit;
    }
    for (String field : new String[] {"workflowInstanceId", "taskId", "approvalId", "formId"}) {
      String value = optionalText(event, field);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String optionalText(JsonNode event, String field) {
    JsonNode value = event.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }
}
