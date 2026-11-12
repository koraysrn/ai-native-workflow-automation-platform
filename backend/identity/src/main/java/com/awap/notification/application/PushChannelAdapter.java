package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Push transport against an FCM/APNs-compatible HTTP endpoint (issue #204). The wire payload is
 * {@code {"token": <device token>, "notification": {"title": ..., "body": ...}}} and the provider
 * message id (FCM returns it as {@code name}) is recorded on the delivery row.
 */
@Component
public class PushChannelAdapter extends HttpProviderAdapter {

  @Autowired
  public PushChannelAdapter(NotificationProviderProperties properties, ObjectMapper objectMapper) {
    this(
        properties.getPush(),
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(properties.getPush().getTimeoutMs(), 1L)))
            .build());
  }

  PushChannelAdapter(
      NotificationProviderProperties.Push settings,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    super(
        "PUSH",
        settings.isEnabled(),
        settings.getEndpoint(),
        settings.getToken(),
        settings.getTimeoutMs(),
        objectMapper,
        httpClient);
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.PUSH;
  }

  @Override
  protected ObjectNode payload(NotificationMessage message) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("token", message.recipient());
    ObjectNode notification = payload.putObject("notification");
    notification.put("title", message.subject());
    notification.put("body", message.body());
    return payload;
  }
}
