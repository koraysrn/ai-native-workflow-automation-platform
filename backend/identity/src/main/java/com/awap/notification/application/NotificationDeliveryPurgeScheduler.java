package com.awap.notification.application;

import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounds the notification delivery table while keeping a configurable audit window. */
@Service
public class NotificationDeliveryPurgeScheduler {

  private final NotificationDeliveryRepository repository;
  private final long retentionDays;

  public NotificationDeliveryPurgeScheduler(
      NotificationDeliveryRepository repository,
      @Value("${awap.notification.retention-days:30}") long retentionDays) {
    this.repository = repository;
    this.retentionDays = retentionDays;
  }

  @Scheduled(cron = "0 30 3 * * *")
  @Transactional
  public void purgeExpired() {
    repository.deleteByCreatedAtBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
  }
}
