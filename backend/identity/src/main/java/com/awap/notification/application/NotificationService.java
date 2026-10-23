package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Notification module skeleton (ADR-003: notification is a separate module of the modular
 * monolith). Faz 1 fills in the delivery pipeline and the {@code NotificationRequestedEvent}
 * consumer.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String variablesJson) {
    // Faz 1: resolve template, render and dispatch via the channel adapter.
    log.debug(
        "Notification queued: tenant={} recipient={} channel={} template={}",
        tenantId,
        recipient,
        channel,
        templateKey);
  }
}
