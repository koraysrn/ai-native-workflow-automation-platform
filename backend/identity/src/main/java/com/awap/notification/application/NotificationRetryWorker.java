package com.awap.notification.application;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationRetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Executes exactly one provider call for a claimed delivery and persists the outcome under the
 * lease. The provider call happens outside any database transaction; the guarded UPDATE plus the
 * outbox write follow in one short tenant transaction.
 */
@Service
public class NotificationRetryWorker {

  public static final String ATTEMPTS_METRIC = "awap.notification.retry.attempts";
  public static final String FAILURES_METRIC = "awap.notification.retry.failures";
  public static final String SUCCESSES_METRIC = "awap.notification.retry.successes";
  public static final String LEASE_CONFLICT_METRIC = "awap.notification.retry.lease.conflicts";
  public static final String PERMANENT_FAILURES_METRIC =
      "awap.notification.retry.permanent.failures";
  public static final String DLQ_METRIC = "awap.notification.retry.dlq";

  private static final Logger log = LoggerFactory.getLogger(NotificationRetryWorker.class);

  private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
      new EnumMap<>(NotificationChannel.class);
  private final NotificationTemplateRenderer renderer;
  private final NotificationDeliveryStateStore stateStore;
  private final OutboxEventPublisher outboxEventPublisher;
  private final KafkaTenantContext tenantContext;
  private final MeterRegistry meterRegistry;
  private final NotificationRetryPolicy retryPolicy;
  private final int maxBodyBytes;
  private final Clock clock;

  @Autowired
  public NotificationRetryWorker(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      NotificationDeliveryStateStore stateStore,
      OutboxEventPublisher outboxEventPublisher,
      KafkaTenantContext tenantContext,
      MeterRegistry meterRegistry,
      @Value("${awap.notification.retry-delays-ms:1000,5000,25000,120000,600000}")
          String retryDelays,
      @Value("${awap.notification.max-attempts:5}") int maxAttempts,
      @Value("${awap.notification.max-body-bytes:262144}") int maxBodyBytes) {
    this(
        channelAdapters,
        renderer,
        stateStore,
        outboxEventPublisher,
        tenantContext,
        meterRegistry,
        NotificationRetryPolicy.fromCommaSeparated(retryDelays, maxAttempts),
        maxBodyBytes,
        Clock.systemUTC());
  }

  NotificationRetryWorker(
      List<NotificationChannelAdapter> channelAdapters,
      NotificationTemplateRenderer renderer,
      NotificationDeliveryStateStore stateStore,
      OutboxEventPublisher outboxEventPublisher,
      KafkaTenantContext tenantContext,
      MeterRegistry meterRegistry,
      NotificationRetryPolicy retryPolicy,
      int maxBodyBytes,
      Clock clock) {
    this.renderer = renderer;
    this.stateStore = stateStore;
    this.outboxEventPublisher = outboxEventPublisher;
    this.tenantContext = tenantContext;
    this.meterRegistry = meterRegistry;
    this.retryPolicy = retryPolicy;
    this.maxBodyBytes = maxBodyBytes;
    this.clock = clock;
    for (NotificationChannelAdapter adapter : channelAdapters) {
      adapters.put(adapter.channel(), adapter);
    }
  }

  /** Processes one claim with one provider call; safe to invoke from any replica. */
  public void deliver(NotificationRetryClaim claim, UUID leaseToken, Instant now) {
    boolean leaseCurrent =
        tenantContext.call(
            claim.tenantId(), () -> stateStore.isLeaseCurrent(claim.id(), leaseToken));
    if (!leaseCurrent) {
      conflict("lease was taken over before dispatch", claim);
      return;
    }

    NotificationChannelAdapter adapter = adapters.get(claim.channel());
    if (adapter == null) {
      failPermanently(
          claim, leaseToken, "UNSUPPORTED_CHANNEL", "No adapter configured for channel.");
      return;
    }

    String body;
    try {
      body = renderer.render(claim.templateKey(), claim.locale(), claim.variablesJson());
    } catch (RuntimeException ex) {
      // A template that cannot render will never render; retrying only burns the queue.
      failPermanently(claim, leaseToken, "TEMPLATE_RENDER_FAILED", rootMessage(ex));
      return;
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
      failPermanently(
          claim,
          leaseToken,
          "PAYLOAD_TOO_LARGE",
          "Notification body exceeds " + maxBodyBytes + " bytes.");
      return;
    }

    NotificationMessage message =
        new NotificationMessage(
            claim.tenantId(),
            claim.recipient(),
            claim.channel(),
            "Notification",
            body,
            claim.recipient());
    int attempts = claim.attempts() + 1;
    try {
      adapter.dispatch(message);
    } catch (RuntimeException ex) {
      counter(ATTEMPTS_METRIC).increment();
      counter(FAILURES_METRIC).increment();
      handleFailure(claim, leaseToken, attempts, rootMessage(ex), now);
      return;
    }

    counter(ATTEMPTS_METRIC).increment();
    Instant sentAt = clock.instant();
    boolean applied =
        tenantContext.call(
            claim.tenantId(),
            () -> {
              boolean updated = stateStore.markSent(claim.id(), leaseToken, attempts, sentAt);
              if (updated) {
                outboxEventPublisher.publish(
                    claim.tenantId(),
                    "NOTIFICATION",
                    claim.id(),
                    "NOTIFICATION_SENT",
                    Map.of(
                        "recipient", claim.recipient(),
                        "channel", claim.channel().name(),
                        "dedupeKey", claim.dedupeKey(),
                        "attempts", attempts));
              }
              return updated;
            });
    if (applied) {
      counter(SUCCESSES_METRIC).increment();
      log.info(
          "Notification {} delivered on attempt {} for tenant {}.",
          claim.id(),
          attempts,
          claim.tenantId());
    } else {
      conflict("SENT write lost the lease", claim);
    }
  }

  private void handleFailure(
      NotificationRetryClaim claim, UUID leaseToken, int attempts, String error, Instant now) {
    Instant lastAttemptAt = now;
    if (retryPolicy.isExhausted(attempts)) {
      boolean applied =
          tenantContext.call(
              claim.tenantId(),
              () -> {
                boolean updated =
                    stateStore.markFailedPermanently(
                        claim.id(), leaseToken, attempts, error, lastAttemptAt);
                if (updated) {
                  publishFailureEvents(claim, attempts, "EXHAUSTED", error);
                }
                return updated;
              });
      if (applied) {
        counter(PERMANENT_FAILURES_METRIC).increment();
        counter(DLQ_METRIC).increment();
        log.warn(
            "Notification {} exhausted {} attempts and was dead-lettered: {}",
            claim.id(),
            attempts,
            error);
      } else {
        conflict("failure write lost the lease", claim);
      }
      return;
    }

    Instant nextAttemptAt = retryPolicy.nextAttemptAt(now, claim.lastAttemptAt(), attempts);
    boolean applied =
        tenantContext.call(
            claim.tenantId(),
            () ->
                stateStore.markRetrying(
                    claim.id(), leaseToken, attempts, error, lastAttemptAt, nextAttemptAt));
    if (applied) {
      log.info(
          "Notification {} scheduled for retry {} at {}: {}",
          claim.id(),
          attempts + 1,
          nextAttemptAt,
          error);
    } else {
      conflict("retry write lost the lease", claim);
    }
  }

  private void failPermanently(
      NotificationRetryClaim claim, UUID leaseToken, String reason, String detail) {
    counter(ATTEMPTS_METRIC).increment();
    counter(FAILURES_METRIC).increment();
    Instant attemptAt = clock.instant();
    int attempts = claim.attempts() + 1;
    boolean applied =
        tenantContext.call(
            claim.tenantId(),
            () -> {
              boolean updated =
                  stateStore.markFailedPermanently(
                      claim.id(), leaseToken, attempts, detail, attemptAt);
              if (updated) {
                publishFailureEvents(claim, attempts, reason, detail);
              }
              return updated;
            });
    if (applied) {
      counter(PERMANENT_FAILURES_METRIC).increment();
      counter(DLQ_METRIC).increment();
      log.warn("Notification {} failed permanently ({}): {}", claim.id(), reason, detail);
    } else {
      conflict("permanent failure write lost the lease", claim);
    }
  }

  private void publishFailureEvents(
      NotificationRetryClaim claim, int attempts, String reason, String detail) {
    Map<String, Object> payload =
        Map.of(
            "recipient",
            claim.recipient(),
            "channel",
            claim.channel().name(),
            "dedupeKey",
            claim.dedupeKey(),
            "attempts",
            attempts,
            "reason",
            reason,
            "lastError",
            detail);
    outboxEventPublisher.publish(
        claim.tenantId(), "NOTIFICATION", claim.id(), "NOTIFICATION_FAILED", payload);
    outboxEventPublisher.publish(
        claim.tenantId(), "NOTIFICATION", claim.id(), "NOTIFICATION_DLQ", payload);
  }

  private void conflict(String detail, NotificationRetryClaim claim) {
    counter(LEASE_CONFLICT_METRIC).increment();
    log.debug("Notification {} skipped: {} (tenant {}).", claim.id(), detail, claim.tenantId());
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }

  private static String rootMessage(RuntimeException ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }
}
