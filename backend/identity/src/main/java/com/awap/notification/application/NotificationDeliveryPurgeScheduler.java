package com.awap.notification.application;

import com.awap.notification.domain.NotificationRetentionPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the notification delivery table with two independent windows: read acknowledgements age
 * out from {@code read_at}, unread notifications only from {@code created_at} after a much longer
 * window. Unread rows are therefore never deleted by the read retention clock, which also keeps
 * deliveries owned by the retry scheduler until they either converge or age out.
 */
@Service
public class NotificationDeliveryPurgeScheduler {

  public static final String PURGED_METRIC = "awap.notification.retention.purged";

  private static final Logger log =
      LoggerFactory.getLogger(NotificationDeliveryPurgeScheduler.class);

  private final NotificationDeliveryRepository repository;
  private final NotificationRetentionPolicy retentionPolicy;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  @Autowired
  public NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRepository repository,
      @Value("${awap.notification.read-retention-days:30}") long readRetentionDays,
      @Value("${awap.notification.unread-retention-days:180}") long unreadRetentionDays,
      MeterRegistry meterRegistry) {
    this(
        repository,
        new NotificationRetentionPolicy(
            Duration.ofDays(readRetentionDays), Duration.ofDays(unreadRetentionDays)),
        Clock.systemUTC(),
        meterRegistry);
  }

  NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRepository repository,
      NotificationRetentionPolicy retentionPolicy,
      Clock clock) {
    this(repository, retentionPolicy, clock, Metrics.globalRegistry);
  }

  NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRepository repository,
      NotificationRetentionPolicy retentionPolicy,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.retentionPolicy = retentionPolicy;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  /** Purges expired read and unread deliveries; returns the per-window deletion counts. */
  @Scheduled(cron = "0 30 3 * * *")
  @Transactional
  public PurgeResult purgeExpired() {
    Instant now = clock.instant();
    int readPurged = repository.purgeReadBefore(offset(retentionPolicy.readCutoff(now)));
    int unreadPurged = repository.purgeUnreadBefore(offset(retentionPolicy.unreadCutoff(now)));
    if (readPurged > 0 || unreadPurged > 0) {
      Counter.builder(PURGED_METRIC).register(meterRegistry).increment(readPurged + unreadPurged);
      log.info(
          "Notification retention purged {} read and {} unread deliveries (windows: read={}, unread={}).",
          readPurged,
          unreadPurged,
          retentionPolicy.readRetention(),
          retentionPolicy.unreadRetention());
    }
    return new PurgeResult(readPurged, unreadPurged);
  }

  private static OffsetDateTime offset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** Outcome of one purge run. */
  public record PurgeResult(int readNotificationsPurged, int unreadNotificationsPurged) {}
}
