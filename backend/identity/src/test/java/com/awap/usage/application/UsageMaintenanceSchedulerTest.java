package com.awap.usage.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UsageMaintenanceSchedulerTest {

  private final UsageReconciliationService reconciliationService =
      mock(UsageReconciliationService.class);
  private final UsageRetentionService retentionService = mock(UsageRetentionService.class);

  @Test
  void nightlyReconciliationCoversTheLastTwoUtcDays() {
    Clock clock = Clock.fixed(Instant.parse("2026-03-30T02:20:00Z"), ZoneOffset.UTC);
    UsageMaintenanceScheduler scheduler =
        new UsageMaintenanceScheduler(reconciliationService, retentionService, clock);

    scheduler.reconcileNightly();

    verify(reconciliationService).reconcile(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 30));
  }

  @Test
  void nightlyRetentionPassesTheCurrentUtcDay() {
    Clock clock = Clock.fixed(Instant.parse("2026-03-30T03:40:00Z"), ZoneOffset.UTC);
    UsageMaintenanceScheduler scheduler =
        new UsageMaintenanceScheduler(reconciliationService, retentionService, clock);

    scheduler.archiveNightly();

    verify(retentionService).archive(LocalDate.of(2026, 3, 30));
  }
}
