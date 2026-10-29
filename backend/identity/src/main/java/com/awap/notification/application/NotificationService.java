package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.DedupePolicy;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDedupeKeyFactory;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.domain.NotificationRetryPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
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
 * deliveries, the tenant template is rendered per locale, and only the <b>first</b> attempt happens
 * here.
 *
 * <p>De-duplication is time-boxed: the key is only unique while {@code dedupeExpiresAt} is active,
 * so a repeat inside the window is suppressed (and counted) while a repeat after it creates a new
 * delivery. The window and the per-template overrides come from {@link
 * NotificationDedupeWindowResolver}; the business key is part of the SHA-256 material.
 *
 * <p>A failure stores {@code RETRYING} plus {@code nextAttemptAt} and returns immediately; no
 * thread ever sleeps inside the consumer transaction. {@link NotificationRetryScheduler} performs
 * the remaining attempts outside Kafka.
 */
@Service
public class NotificationService {

  public static final String SUPPRESSED_METRIC = "awap.notification.dedupe.suppressed";

  static final String DEFAULT_EVENT_TYPE = "NOTIFICATION_REQUESTED";

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
  private final MeterRegistry meterRegistry;
  private final NotificationDedupeKeyFactory dedupeKeyFactory;
  private final NotificationDedupeWindowResolver dedupeWindowResolver;

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
        Clock.systemUTC(),
        Metrics.globalRegistry);
  }

  @Autowired
  public NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      NotificationDedupeKeyFactory dedupeKeyFactory,
      NotificationDedupeWindowResolver dedupeWindowResolver,
      MeterRegistry meterRegistry,
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
        Clock.systemUTC(),
        meterRegistry,
        dedupeKeyFactory,
        dedupeWindowResolver);
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
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        retryPolicy,
        maxBodyBytes,
        firstAttemptGraceMs,
        clock,
        Metrics.globalRegistry);
  }

  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      NotificationRetryPolicy retryPolicy,
      int maxBodyBytes,
      long firstAttemptGraceMs,
      Clock clock,
      MeterRegistry meterRegistry) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        retryPolicy,
        maxBodyBytes,
        firstAttemptGraceMs,
        clock,
        meterRegistry,
        new NotificationDedupeKeyFactory(),
        new NotificationDedupeWindowResolver(new NotificationDedupeProperties(), renderer));
  }

  /** Backward-compatible constructor for the pre-scheduler retry tests. */
  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      long[] retryDelaysMs,
      Sleeper sleeper) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        retryDelaysMs,
        sleeper,
        new NotificationDedupeKeyFactory(),
        new NotificationDedupeWindowResolver(new NotificationDedupeProperties(), renderer),
        Metrics.globalRegistry,
        Clock.systemUTC());
  }

  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      long[] retryDelaysMs,
      Sleeper sleeper,
      NotificationDedupeKeyFactory dedupeKeyFactory,
      NotificationDedupeWindowResolver dedupeWindowResolver,
      MeterRegistry meterRegistry,
      Clock clock) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        new NotificationRetryPolicy(retryDelaysMs, NotificationRetryPolicy.DEFAULT_MAX_ATTEMPTS),
        262_144,
        60_000L,
        clock,
        meterRegistry,
        dedupeKeyFactory,
        dedupeWindowResolver);
  }

  NotificationService(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      OutboxEventPublisher outboxEventPublisher,
      NotificationDeliveryRepository deliveryRepository,
      NotificationRetryPolicy retryPolicy,
      int maxBodyBytes,
      long firstAttemptGraceMs,
      Clock clock,
      MeterRegistry meterRegistry,
      NotificationDedupeKeyFactory dedupeKeyFactory,
      NotificationDedupeWindowResolver dedupeWindowResolver) {
    this.renderer = renderer;
    this.outboxEventPublisher = outboxEventPublisher;
    this.deliveryRepository = deliveryRepository;
    this.retryPolicy = retryPolicy;
    this.maxBodyBytes = maxBodyBytes;
    this.firstAttemptGraceMs = firstAttemptGraceMs;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.dedupeKeyFactory = dedupeKeyFactory;
    this.dedupeWindowResolver = dedupeWindowResolver;
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
    send(tenantId, recipient, channel, templateKey, "en", variablesJson, null, null, null);
  }

  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String locale,
      String variablesJson,
      String dedupeKey) {
    send(tenantId, recipient, channel, templateKey, locale, variablesJson, dedupeKey, null, null);
  }

  /**
   * Performs the first (and only) in-consumer attempt and returns fast. Retries are persisted, not
   * slept on; the Kafka offset is committed only after this method returns, i.e. after the delivery
   * row (and any failure schedule) is durable.
   *
   * @param businessKey workflow instance / task / approval / form identity when available
   * @param eventType produces the de-duplication policy; unknown values fail safe as transactional
   */
  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String locale,
      String variablesJson,
      String dedupeKey,
      String businessKey,
      String eventType) {
    NotificationChannelAdapter adapter = adapters.get(channel);
    if (adapter == null) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "No adapter configured for channel " + channel + ".");
    }
    DedupePolicy policy =
        DedupePolicy.forEventType(eventType == null ? DEFAULT_EVENT_TYPE : eventType);
    String effectiveKey =
        dedupeKeyFactory.build(
            tenantId,
            channel,
            recipient,
            templateKey,
            policy,
            businessKey,
            variablesJson,
            dedupeKey);
    Instant now = clock.instant();
    Instant dedupeExpiresAt = now.plusSeconds(dedupeWindowResolver.windowSeconds(templateKey));
    if (deliveryRepository != null) {
      Optional<NotificationDelivery> existing =
          deliveryRepository.findByTenantIdAndDedupeKey(tenantId, effectiveKey);
      if (existing.isPresent()) {
        NotificationDelivery row = existing.get();
        if (row.isDedupeActiveAt(now) || !row.isTerminal()) {
          // An active window is the system of record, and a pending/retrying row is owned by the
          // scheduler. A re-delivered Kafka record must not send again.
          recordSuppressed(policy, templateKey, "existing");
          log.info("Duplicate notification {} for tenant {} suppressed.", effectiveKey, tenantId);
          return;
        }
      }
    }

    NotificationDelivery delivery =
        new NotificationDelivery(
            tenantId,
            effectiveKey,
            channel,
            recipient,
            templateKey,
            locale,
            variablesJson,
            businessKey,
            dedupeExpiresAt);
    // Durable before any provider call: a crash cannot lose the delivery.
    delivery.scheduleFirstAttempt(now.plusMillis(firstAttemptGraceMs));
    if (!persistNewDelivery(delivery, policy, templateKey)) {
      return;
    }

    NotificationTemplateRenderer.RenderedNotificationTemplate rendered;
    try {
      rendered = renderer.render(tenantId, templateKey, locale, variablesJson);
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
    String body = rendered.body();
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
        new NotificationMessage(tenantId, recipient, channel, rendered.subject(), body, recipient);
    long startedNanos = System.nanoTime();
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
      meterRegistry
          .counter("awap.notification.attempts", "channel", channel.name(), "result", "failed")
          .increment();
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
    recordDispatchMetrics(channel, "sent", startedNanos);
  }

  /**
   * Records delivery latency, which the notification panels and retry-lag alert use (issue #130).
   * The retry lag itself is recorded by {@link NotificationRetryScheduler} at claim time.
   */
  private void recordDispatchMetrics(
      NotificationChannel channel, String result, long startedNanos) {
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    meterRegistry
        .counter("awap.notification.deliveries", "channel", channel.name(), "result", result)
        .increment();
    meterRegistry
        .timer("awap.notification.dispatch.latency", "channel", channel.name())
        .record(elapsed);
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

  private boolean persistNewDelivery(
      NotificationDelivery delivery, DedupePolicy policy, String templateKey) {
    if (deliveryRepository == null) {
      return true;
    }
    try {
      deliveryRepository.save(delivery);
      return true;
    } catch (DataIntegrityViolationException ex) {
      // A concurrent consumer inserted the same (tenant, dedupe_key); the unique constraint wins
      // and this delivery must not send a duplicate.
      recordSuppressed(policy, templateKey, "constraint");
      log.info("Concurrent duplicate notification {} suppressed.", delivery.getDedupeKey());
      return false;
    }
  }

  private void persistExistingDelivery(NotificationDelivery delivery) {
    if (deliveryRepository != null) {
      deliveryRepository.save(delivery);
    }
  }

  private void recordSuppressed(DedupePolicy policy, String templateKey, String reason) {
    Counter.builder(SUPPRESSED_METRIC)
        .description("Notifications suppressed by the active de-duplication window.")
        .tag("policy", policy.name())
        .tag("template", templateKey == null ? "unknown" : templateKey)
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
  }

  private static String rootMessage(RuntimeException ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }

  /**
   * Test seam kept for the legacy constructor; the retry scheduler never sleeps in the consumer.
   */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis);
  }
}
