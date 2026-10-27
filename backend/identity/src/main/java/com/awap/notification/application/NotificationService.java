package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.DedupePolicy;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDedupeKeyFactory;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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
 * deliveries, the template is rendered per locale, transient failures are retried with the
 * configured exponential backoff and the outcome is reported through the outbox.
 *
 * <p>De-duplication is time-boxed: the key is only unique while {@code dedupeExpiresAt} is active,
 * so a repeat inside the window is suppressed (and counted) while a repeat after it creates a new
 * delivery. The window and the per-template overrides come from {@link
 * NotificationDedupeWindowResolver}; the business key is part of the SHA-256 material.
 */
@Service
public class NotificationService {

  public static final String SUPPRESSED_METRIC = "awap.notification.dedupe.suppressed";

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  static final int MAX_ATTEMPTS = 3;
  static final String DEFAULT_EVENT_TYPE = "NOTIFICATION_REQUESTED";

  private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
      new EnumMap<>(NotificationChannel.class);
  private final NotificationTemplateRenderer renderer;
  private final OutboxEventPublisher outboxEventPublisher;
  private final NotificationDeliveryRepository deliveryRepository;
  private final long[] retryDelaysMs;
  private final Sleeper sleeper;
  private final NotificationDedupeKeyFactory dedupeKeyFactory;
  private final NotificationDedupeWindowResolver dedupeWindowResolver;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

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
      NotificationDedupeKeyFactory dedupeKeyFactory,
      NotificationDedupeWindowResolver dedupeWindowResolver,
      MeterRegistry meterRegistry,
      @Value("${awap.notification.retry-delays-ms:1000,5000,25000}") String retryDelays) {
    this(
        channelAdapters,
        renderer,
        outboxEventPublisher,
        deliveryRepository,
        parseDelays(retryDelays),
        Sleeper.system(),
        dedupeKeyFactory,
        dedupeWindowResolver,
        meterRegistry,
        Clock.systemUTC());
  }

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
        new SimpleMeterRegistry(),
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
    this.renderer = renderer;
    this.outboxEventPublisher = outboxEventPublisher;
    this.deliveryRepository = deliveryRepository;
    this.retryDelaysMs = retryDelaysMs;
    this.sleeper = sleeper;
    this.dedupeKeyFactory = dedupeKeyFactory;
    this.dedupeWindowResolver = dedupeWindowResolver;
    this.meterRegistry = meterRegistry;
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

  public void send(
      UUID tenantId,
      String recipient,
      NotificationChannel channel,
      String templateKey,
      String locale,
      String variablesJson,
      String dedupeKey) {
    send(
        tenantId,
        recipient,
        channel,
        templateKey,
        locale,
        variablesJson,
        dedupeKey,
        null,
        DEFAULT_EVENT_TYPE);
  }

  /**
   * Sends one notification subject to the TTL/business-key de-duplication policy.
   *
   * @param dedupeKey optional explicit key (hashed together with the tenant before use)
   * @param businessKey workflow instance / task / approval / form identity when available
   * @param eventType event type used to resolve the {@link DedupePolicy}
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
    DedupePolicy policy = DedupePolicy.forEventType(eventType);
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
    NotificationDelivery delivery =
        prepareDelivery(
            tenantId,
            channel,
            recipient,
            templateKey,
            effectiveKey,
            businessKey,
            dedupeExpiresAt,
            now,
            policy);
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
                "dedupeKey", effectiveKey,
                "policy", policy.name(),
                "dedupeExpiresAt", dedupeExpiresAt.toString()));
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
            "recipient",
            recipient,
            "channel",
            channel.name(),
            "dedupeKey",
            effectiveKey,
            "policy",
            policy.name()));
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
      String dedupeKey,
      String businessKey,
      Instant dedupeExpiresAt,
      Instant now,
      DedupePolicy policy) {
    if (deliveryRepository == null) {
      return new NotificationDelivery(
          tenantId, dedupeKey, channel, recipient, templateKey, businessKey, dedupeExpiresAt, now);
    }
    Optional<NotificationDelivery> existing =
        deliveryRepository.findByTenantIdAndDedupeKey(tenantId, dedupeKey);
    if (existing.isPresent() && existing.get().isDedupeActiveAt(now)) {
      if ("SENT".equals(existing.get().getStatus())) {
        recordSuppressed(policy, templateKey, "existing");
        return null;
      }
      // A pending/retrying row is the current owner of this window.
      return existing.get();
    }
    NotificationDelivery created =
        new NotificationDelivery(
            tenantId,
            dedupeKey,
            channel,
            recipient,
            templateKey,
            businessKey,
            dedupeExpiresAt,
            now);
    try {
      return deliveryRepository.save(created);
    } catch (DataIntegrityViolationException ex) {
      Optional<NotificationDelivery> winner =
          deliveryRepository.findByTenantIdAndDedupeKey(tenantId, dedupeKey);
      if (winner.isEmpty()) {
        // Not a duplicate key (for example the tenant guard rejected the row): never hide it.
        throw ex;
      }
      if (winner.get().isDedupeActiveAt(now) && "SENT".equals(winner.get().getStatus())) {
        recordSuppressed(policy, templateKey, "constraint");
        return null;
      }
      return winner.filter(delivery -> !"SENT".equals(delivery.getStatus())).orElse(null);
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

  private void saveDelivery(NotificationDelivery delivery) {
    if (deliveryRepository != null) {
      deliveryRepository.save(delivery);
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
