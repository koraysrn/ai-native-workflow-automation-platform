package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Notification dispatch orchestration. A durable delivery row de-duplicates repeated Kafka
 * deliveries, the template is rendered per locale, transient failures are retried with the
 * configured exponential backoff and the outcome is reported through the outbox.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  static final int MAX_ATTEMPTS = 3;

  private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
      new EnumMap<>(NotificationChannel.class);
  private final NotificationTemplateRenderer renderer;
  private final OutboxEventPublisher outboxEventPublisher;
  private final NotificationDeliveryRepository deliveryRepository;
  private final long[] retryDelaysMs;
  private final Sleeper sleeper;

  /** Backward-compatible test constructor: no persistence, no real sleeping. */
  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        null,
        new long[] {1_000L, 5_000L, 25_000L},
        millis -> {});
  }

  @Autowired
  public NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      @Value("${awap.notification.retry-delays-ms:1000,5000,25000}") String retryDelays) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        parseDelays(retryDelays),
        Sleeper.system());
  }

  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      long[] retryDelaysMs,
      Sleeper sleeper) {
    this.renderer = renderer;
    this.outboxEventPublisher = outboxEventPublisher;
    this.deliveryRepository = deliveryRepository;
    this.retryDelaysMs = retryDelaysMs;
    this.sleeper = sleeper;
    for (NotificationChannelAdapter adapter : channelAdapters) {
      adapters.put(adapter.channel(), adapter);
    }
  }

  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String variablesJson) {
    send(tenantId, recipient, channel, templateKey, "en", variablesJson, null);
  }

  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String locale,
      String variablesJson,
      String dedupeKey) {
    NotificationChannelAdapter adapter = adapters.get(channel);
    if (adapter == null) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "No adapter configured for channel " + channel + ".");
    }
    String effectiveKey =
        dedupeKey(dedupeKey, tenantId, channel, recipient, templateKey, variablesJson);
    NotificationDelivery delivery =
        prepareDelivery(tenantId, channel, recipient, templateKey, effectiveKey);
    if (delivery == null) {
      log.info("Duplicate notification {} for tenant {} suppressed.", effectiveKey, tenantId);
      return;
    }

    String body = renderer.render(templateKey, locale, variablesJson);
    NotificationMessage message =
        new NotificationMessage(tenantId, recipient, channel, "Notification", body, recipient);

    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        adapter.dispatch(message);
        delivery.recordAttempt();
        delivery.markSent();
        saveDelivery(delivery);
        outboxEventPublisher.publish(
            tenantId,
            "NOTIFICATION",
            delivery.getId(),
            "NOTIFICATION_SENT",
            Map.of(
                "recipient", recipient,
                "channel", channel.name(),
                "dedupeKey", effectiveKey));
        return;
      } catch (RuntimeException ex) {
        lastFailure = ex;
        delivery.recordAttempt();
        delivery.markFailed(ex.getMessage());
        saveDelivery(delivery);
        log.warn(
            "Notification attempt {}/{} failed for channel {}: {}",
            attempt,
            MAX_ATTEMPTS,
            channel,
            ex.getMessage());
        if (attempt < MAX_ATTEMPTS) {
          sleeper.sleep(retryDelaysMs[Math.min(attempt - 1, retryDelaysMs.length - 1)]);
        }
      }
    }

    outboxEventPublisher.publish(
        tenantId,
        "NOTIFICATION",
        delivery.getId(),
        "NOTIFICATION_FAILED",
        Map.of(
            "recipient", recipient,
            "channel", channel.name(),
            "dedupeKey", effectiveKey));
    throw lastFailure;
  }

  /** Lists in-app/web notifications and delivery history for the tenant dashboard. */
  public List<NotificationDelivery> deliveries(UUID tenantId) {
    return deliveryRepository == null
        ? List.of()
        : deliveryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  private NotificationDelivery prepareDelivery(
      UUID tenantId,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String dedupeKey) {
    if (deliveryRepository == null) {
      return new NotificationDelivery(tenantId, dedupeKey, channel, recipient, templateKey);
    }
    Optional<NotificationDelivery> existing =
        deliveryRepository.findByTenantIdAndDedupeKey(tenantId, dedupeKey);
    if (existing.isPresent()) {
      return "SENT".equals(existing.get().getStatus()) ? null : existing.get();
    }
    NotificationDelivery created =
        new NotificationDelivery(tenantId, dedupeKey, channel, recipient, templateKey);
    try {
      return deliveryRepository.save(created);
    } catch (DataIntegrityViolationException ex) {
      return deliveryRepository
          .findByTenantIdAndDedupeKey(tenantId, dedupeKey)
          .filter(delivery -> !"SENT".equals(delivery.getStatus()))
          .orElse(null);
    }
  }

  private void saveDelivery(NotificationDelivery delivery) {
    if (deliveryRepository != null) {
      deliveryRepository.save(delivery);
    }
  }

  private String dedupeKey(
      String explicit,
      UUID tenantId,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String variablesJson) {
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    String source =
        tenantId
            + "|"
            + channel
            + "|"
            + recipient
            + "|"
            + templateKey
            + "|"
            + (variablesJson == null ? "" : variablesJson);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static long[] parseDelays(String spec) {
    return java.util.Arrays.stream(spec.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .mapToLong(Long::parseLong)
        .toArray();
  }

  /** Test seam so retry tests do not sleep for real. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis);

    static Sleeper system() {
      return millis -> {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Notification retry sleep interrupted.", ex);
        }
      };
    }
  }
}
