package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.util.UUID;

/** A fully-rendered notification ready for dispatch through one channel. */
public record NotificationMessage(
    UUID tenantId,
    String recipient,
    NotificationChannel channel,
    String subject,
    String body,
    String webhookUrl) {

  public static NotificationMessage of(
      UUID tenantId, String recipient, NotificationChannel channel, String subject, String body) {
    return new NotificationMessage(tenantId, recipient, channel, subject, body, null);
  }
}
