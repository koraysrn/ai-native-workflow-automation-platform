package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Notification dispatch orchestration. Renders the template, selects the per-channel adapter and
 * retries transient failures before reporting the outcome through the durable outbox. A final
 * failure is rethrown so the Kafka error handler can route the message to the DLQ.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  static final int MAX_ATTEMPTS = 3;

  private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
      new EnumMap<>(NotificationChannel.class);
  private final NotificationTemplateRenderer renderer;
  private final OutboxEventPublisher outboxEventPublisher;

  public NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher) {
    this.renderer = renderer;
    this.outboxEventPublisher = outboxEventPublisher;
    for (NotificationChannelAdapter adapter : channelAdapters) {
      adapters.put(adapter.channel(), adapter);
    }
  }

  /** Dispatch a notification, returning immediately after it is handed to the channel adapter. */
  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String variablesJson) {
    NotificationChannelAdapter adapter = adapters.get(channel);
    if (adapter == null) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "No adapter configured for channel " + channel + ".");
    }
    String body = renderer.render(templateKey, variablesJson);
    NotificationMessage message =
        new NotificationMessage(tenantId, recipient, channel, "Notification", body, recipient);

    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        adapter.dispatch(message);
        outboxEventPublisher.publish(
            tenantId,
            "NOTIFICATION",
            UUID.randomUUID(),
            "NOTIFICATION_SENT",
            Map.of("recipient", recipient, "channel", channel.name()));
        return;
      } catch (RuntimeException ex) {
        lastFailure = ex;
        log.warn(
            "Notification dispatch attempt {}/{} failed for channel {}: {}",
            attempt,
            MAX_ATTEMPTS,
            channel,
            ex.getMessage());
      }
    }

    outboxEventPublisher.publish(
        tenantId,
        "NOTIFICATION",
        UUID.randomUUID(),
        "NOTIFICATION_FAILED",
        Map.of("recipient", recipient, "channel", channel.name()));
    throw lastFailure;
  }
}
