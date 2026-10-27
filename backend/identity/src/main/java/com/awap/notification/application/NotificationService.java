package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.domain.NotificationRetryPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * deliveries, the template is rendered per locale, and only the <b>first</b> attempt happens here.
 *
 * <p>A failure stores {@code RETRYING} plus {@code nextAttemptAt} and returns immediately; no
 * thread ever sleeps inside the consumer transaction. {@link NotificationRetryScheduler} performs
 * the remaining attempts outside Kafka.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
      new EnumMap<>(NotificationChannel.class);
  private final NotificationTemplateRenderer renderer;
  private final OutboxEventPublisher outboxEventPublisher;
  private final NotificationDeliveryRepository deliveryRepository;
  private final NotificationRetryPolicy retryPolicy;
  private final int maxBodyBytes;
  private final long firstAttemptGraceMs;
  private final Clock clock;

  /** Test convenience: no persistence, default policy. */
  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        null,
        NotificationRetryPolicy.defaults(),
        262_144,
        60_000L,
        Clock.systemUTC());
  }

  @Autowired
  public NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      @Value("${awap.notification.retry-delays-ms:1000,5000,25000,120000,600000}")
          String retryDelays,
      @Value("${awap.notification.max-attempts:5}") int maxAttempts,
      @Value("${awap.notification.max-body-bytes:262144}") int maxBodyBytes,
      @Value("${awap.notification.first-attempt-grace-ms:60000}") long firstAttemptGraceMs) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        NotificationRetryPolicy.fromCommaSeparated(retryDelays, maxAttempts),
        maxBodyBytes,
        firstAttemptGraceMs,
        Clock.systemUTC());
  }

  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      NotificationRetryPolicy retryPolicy,
      int maxBodyBytes,
      long firstAttemptGraceMs,
      Clock clock) {
    this.renderer = renderer;
    this.outboxEventPublisher = outboxEventPublisher;
    this.deliveryRepository = deliveryRepository;
    this.retryPolicy = retryPolicy;
    this.maxBodyBytes = maxBodyBytes;
    this.firstAttemptGraceMs = firstAttemptGraceMs;
    this.clock = clock;
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

  /**
   * Performs the first (and only) in-consumer attempt and returns fast. Retries are persisted, not
   * slept on; the Kafka offset is committed only after this method returns, i.e. after the delivery
   * row (and any failure schedule) is durable.
   */
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
    if (deliveryRepository != null
        && deliveryRepository.findByTenantIdAndDedupeKey(tenantId, effectiveKey).isPresent()) {
      // Any existing row is the system of record: SENT is a duplicate, PENDING/RETRYING is owned by
      // the scheduler and FAILED is terminal. A re-delivered Kafka record must not send again.
      log.info("Duplicate notification {} for tenant {} suppressed.", effectiveKey, tenantId);
      return;
    }

    Instant now = clock.instant();
    NotificationDelivery delivery =
        new NotificationDelivery(
            tenantId, effectiveKey, channel, recipient, templateKey, locale, variablesJson);
    // Durable before any provider call: a crash cannot lose the delivery.
    delivery.scheduleFirstAttempt(now.plusMillis(firstAttemptGraceMs));
    if (!persistNewDelivery(delivery)) {
      return;
    }

    String body;
    try {
      body = renderer.render(templateKey, locale, variablesJson);
    } catch (RuntimeException ex) {
      failPermanently(
          delivery,
          recipient,
          channel,
          effectiveKey,
          "TEMPLATE_RENDER_FAILED",
          rootMessage(ex),
          now);
      return;
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
      failPermanently(
          delivery,
          recipient,
          channel,
          effectiveKey,
          "PAYLOAD_TOO_LARGE",
          "Notification body exceeds " + maxBodyBytes + " bytes.",
          now);
      return;
    }

    NotificationMessage message =
        new NotificationMessage(tenantId, recipient, channel, "Notification", body, recipient);
    try {
      adapter.dispatch(message);
    } catch (RuntimeException ex) {
      Instant attemptAt = clock.instant();
      delivery.recordAttempt(attemptAt);
      delivery.markRetrying(
          rootMessage(ex),
          retryPolicy.nextAttemptAt(attemptAt, attemptAt, delivery.getAttempts()),
          attemptAt);
      persistExistingDelivery(delivery);
      log.warn(
          "Notification first attempt failed for channel {}; scheduled retry at {}: {}",
          channel,
          delivery.getNextAttemptAt(),
          rootMessage(ex));
      return;
    }

    Instant sentAt = clock.instant();
    delivery.recordAttempt(sentAt);
    delivery.markSent(sentAt);
    persistExistingDelivery(delivery);
    outboxEventPublisher.publish(
        tenantId,
        "NOTIFICATION",
        delivery.getId(),
        "NOTIFICATION_SENT",
        Map.of(
            "recipient",
            recipient,
            "channel",
            channel.name(),
            "dedupeKey",
            effectiveKey,
            "attempts",
            delivery.getAttempts()));
  }

  /** Lists in-app/web notifications and delivery history for the tenant dashboard. */
  public List<NotificationDelivery> deliveries(UUID tenantId) {
    return deliveryRepository == null
        ? List.of()
        : deliveryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  private void failPermanently(
      NotificationDelivery delivery,
      String recipient,
      NotificationChannel channel,
      String dedupeKey,
      String reason,
      String detail,
      Instant now) {
    delivery.recordAttempt(now);
    delivery.markFailedPermanently(detail, now);
    persistExistingDelivery(delivery);
    Map<String, Object> payload =
        Map.of(
            "recipient", recipient,
            "channel", channel.name(),
            "dedupeKey", dedupeKey,
            "attempts", delivery.getAttempts(),
            "reason", reason,
            "lastError", detail);
    outboxEventPublisher.publish(
        delivery.getTenantId(), "NOTIFICATION", delivery.getId(), "NOTIFICATION_FAILED", payload);
    outboxEventPublisher.publish(
        delivery.getTenantId(), "NOTIFICATION", delivery.getId(), "NOTIFICATION_DLQ", payload);
    log.warn("Notification {} failed permanently ({}): {}", delivery.getId(), reason, detail);
  }

  private boolean persistNewDelivery(NotificationDelivery delivery) {
    if (deliveryRepository == null) {
      return true;
    }
    try {
      deliveryRepository.save(delivery);
      return true;
    } catch (DataIntegrityViolationException ex) {
      // A concurrent consumer inserted the same (tenant, dedupe_key); the unique constraint wins
      // and this delivery must not send a duplicate.
      log.info("Concurrent duplicate notification {} suppressed.", delivery.getDedupeKey());
      return false;
    }
  }

  private void persistExistingDelivery(NotificationDelivery delivery) {
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

  private static String rootMessage(RuntimeException ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }
}
