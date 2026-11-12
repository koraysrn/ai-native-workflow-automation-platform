package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SMS transport against a provider-agnostic HTTP gateway (issue #204). The wire payload is {@code
 * {"to": <E.164 number>, "message": <body>, "sender": <sender id>}} and the response id is recorded
 * on the delivery row as the provider message id.
 */
@Component
public class SmsChannelAdapter extends HttpProviderAdapter {

  private final NotificationProviderProperties.Sms settings;

  @Autowired
  public SmsChannelAdapter(NotificationProviderProperties properties, ObjectMapper objectMapper) {
    this(
        properties.getSms(),
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(properties.getSms().getTimeoutMs(), 1L)))
            .build());
  }

  SmsChannelAdapter(
      NotificationProviderProperties.Sms settings,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    super(
        "SMS",
        settings.isEnabled(),
        settings.getEndpoint(),
        settings.getToken(),
        settings.getTimeoutMs(),
        objectMapper,
        httpClient);
    this.settings = settings;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SMS;
  }

  @Override
  protected ObjectNode payload(NotificationMessage message) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("to", message.recipient());
    payload.put("message", message.body());
    payload.put("sender", settings.getSender());
    return payload;
  }
}
