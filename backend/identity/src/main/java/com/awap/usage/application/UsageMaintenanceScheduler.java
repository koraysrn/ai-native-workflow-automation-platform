package com.awap.usage.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly reconciliation and retention passes. Both run on a UTC clock so the window boundaries do
 * not shift with the host timezone.
 */
@Component
public class UsageMaintenanceScheduler {

  static final int RECONCILIATION_LOOKBACK_DAYS = 2;

  private final UsageReconciliationService reconciliationService;
  private final UsageRetentionService retentionService;
  private final Clock clock;

  @Autowired
  public UsageMaintenanceScheduler(
      UsageReconciliationService reconciliationService, UsageRetentionService retentionService) {
    this(reconciliationService, retentionService, Clock.systemUTC());
  }

  UsageMaintenanceScheduler(
      UsageReconciliationService reconciliationService,
      UsageRetentionService retentionService,
      Clock clock) {
    this.reconciliationService = reconciliationService;
    this.retentionService = retentionService;
    this.clock = clock;
  }

  @Scheduled(cron = "${awap.usage.reconciliation-cron:0 20 2 * * *}")
  public void reconcileNightly() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    reconciliationService.reconcile(today.minusDays(RECONCILIATION_LOOKBACK_DAYS), today);
  }

  @Scheduled(cron = "${awap.usage.retention-cron:0 40 3 * * *}")
  public void archiveNightly() {
    retentionService.archive(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
  }
}
