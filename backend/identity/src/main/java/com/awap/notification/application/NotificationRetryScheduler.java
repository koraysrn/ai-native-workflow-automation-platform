package com.awap.notification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Moves notification retries out of the Kafka consumer transaction. Every five seconds it claims
 * due rows through a lease (multi-replica safe), performs one provider call per claim and persists
 * the outcome. Per-tenant capping in the claim query keeps one tenant from starving the queue.
 */
@Service
@ConditionalOnProperty(
    name = "awap.notification.retry-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationRetryScheduler {

  public static final String CLAIMS_METRIC = "awap.notification.retry.claims";
  public static final String LAG_METRIC = "awap.notification.retry.lag";
  public static final String BATCH_ERRORS_METRIC = "awap.notification.retry.batch.errors";

  private static final Logger log = LoggerFactory.getLogger(NotificationRetryScheduler.class);

  private final NotificationRetryClaimRepository claimRepository;
  private final NotificationRetryWorker worker;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final int batchSize;
  private final int perTenantLimit;
  private final Duration lease;

  @Autowired
  public NotificationRetryScheduler(
      NotificationRetryClaimRepository claimRepository,
      NotificationRetryWorker worker,
      MeterRegistry meterRegistry,
      @Value("${awap.notification.retry-batch-size:50}") int batchSize,
      @Value("${awap.notification.retry-per-tenant-limit:5}") int perTenantLimit,
      @Value("${awap.notification.retry-lease-seconds:120}") long leaseSeconds) {
    this(
        claimRepository,
        worker,
        meterRegistry,
        Clock.systemUTC(),
        batchSize,
        perTenantLimit,
        Duration.ofSeconds(Math.max(1L, leaseSeconds)));
  }

  NotificationRetryScheduler(
      NotificationRetryClaimRepository claimRepository,
      NotificationRetryWorker worker,
      MeterRegistry meterRegistry,
      Clock clock,
      int batchSize,
      int perTenantLimit,
      Duration lease) {
    this.claimRepository = claimRepository;
    this.worker = worker;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
    this.batchSize = batchSize;
    this.perTenantLimit = perTenantLimit;
    this.lease = lease;
  }

  @Scheduled(fixedDelayString = "${awap.notification.retry-interval-ms:5000}")
  public void processDueRetries() {
    Instant now = clock.instant();
    UUID leaseToken = UUID.randomUUID();
    List<NotificationRetryClaim> claims;
    try {
      claims = claimRepository.claimDue(now, leaseToken, lease, batchSize, perTenantLimit);
    } catch (RuntimeException ex) {
      // The database is momentarily unavailable; the next tick retries. Nothing is lost because
      // the delivery rows stay durable and unleased.
      counter(BATCH_ERRORS_METRIC).increment();
      log.error("Could not claim due notification retries", ex);
      return;
    }
    if (claims.isEmpty()) {
      return;
    }
    counter(CLAIMS_METRIC).increment(claims.size());
    for (NotificationRetryClaim claim : claims) {
      recordLag(claim, now);
      try {
        worker.deliver(claim, leaseToken, now);
      } catch (RuntimeException ex) {
        // One broken delivery must not block the rest of the batch; the lease expires and the row
        // is retried by another replica.
        counter(BATCH_ERRORS_METRIC).increment();
        log.error("Notification retry failed for delivery {}", claim.id(), ex);
      }
    }
  }

  private void recordLag(NotificationRetryClaim claim, Instant now) {
    if (claim.dueAt() == null) {
      return;
    }
    long lagMs = Math.max(0L, Duration.between(claim.dueAt(), now).toMillis());
    Timer.builder(LAG_METRIC).register(meterRegistry).record(Duration.ofMillis(lagMs));
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }
}
