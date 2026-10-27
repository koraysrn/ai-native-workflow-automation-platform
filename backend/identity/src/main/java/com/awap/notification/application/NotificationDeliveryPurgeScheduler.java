package com.awap.notification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounds the notification delivery table while keeping a configurable audit window: delivered and
 * permanently failed rows live for {@code retention-days} (30 by default), retry rows that never
 * converge are dropped after {@code retry-retention-days} (7 by default).
 */
@Service
public class NotificationDeliveryPurgeScheduler {

  public static final String PURGED_METRIC = "awap.notification.retention.purged";

  private static final Logger log =
      LoggerFactory.getLogger(NotificationDeliveryPurgeScheduler.class);

  private final NotificationDeliveryRetentionRepository repository;
  private final long retentionDays;
  private final long retryRetentionDays;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  @Autowired
  public NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRetentionRepository repository,
      @Value("${awap.notification.retention-days:30}") long retentionDays,
      @Value("${awap.notification.retry-retention-days:7}") long retryRetentionDays,
      MeterRegistry meterRegistry) {
    this(repository, retentionDays, retryRetentionDays, Clock.systemUTC(), meterRegistry);
  }

  NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRetentionRepository repository,
      long retentionDays,
      long retryRetentionDays,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.retentionDays = retentionDays;
    this.retryRetentionDays = retryRetentionDays;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(cron = "0 30 3 * * *")
  public void purgeExpired() {
    Instant now = clock.instant();
    long purged =
        repository.purge(
            now.minus(retentionDays, ChronoUnit.DAYS),
            now.minus(retryRetentionDays, ChronoUnit.DAYS));
    if (purged > 0) {
      Counter.builder(PURGED_METRIC).register(meterRegistry).increment(purged);
      log.info("Purged {} notification delivery row(s).", purged);
    }
  }
}
