package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.NotificationChannel;
import com.awap.webhook.infrastructure.WebhookClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Webhook adapter. Reuses the existing SSRF-guarded, allowlisted {@link WebhookClient} so outbound
 * notification webhooks inherit the same SSRF / allowlist / HMAC protections as workflow webhooks.
 */
@Component
public class WebhookChannelAdapter implements NotificationChannelAdapter {

  private final WebhookClient webhookClient;
  private final ObjectMapper objectMapper;

  public WebhookChannelAdapter(WebhookClient webhookClient, ObjectMapper objectMapper) {
    this.webhookClient = webhookClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.WEBHOOK;
  }

  @Override
  public void dispatch(NotificationMessage message) {
    if (message.webhookUrl() == null || message.webhookUrl().isBlank()) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Webhook notification is missing a target URL.");
    }
    webhookClient.post(message.webhookUrl(), toJson(message));
  }

  private String toJson(NotificationMessage message) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "tenantId", message.tenantId().toString(),
              "recipient", message.recipient(),
              "subject", message.subject(),
              "body", message.body()));
    } catch (JsonProcessingException ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to serialize webhook payload: " + ex.getMessage());
    }
  }
}
