package com.awap.usage.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention/archive policy (issue #125 risk 7): counters older than {@code
 * awap.usage.retention-days} (400 by default) and every counter of a soft-deleted tenant are moved
 * to {@code usage_counter_archive} before the live rows and their raw events are removed.
 */
@Service
public class UsageRetentionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(UsageRetentionService.class);

  private final UsageCounterStore store;
  private final MeterRegistry meterRegistry;
  private final long retentionDays;

  public UsageRetentionService(
      UsageCounterStore store,
      MeterRegistry meterRegistry,
      @Value("${awap.usage.retention-days:400}") long retentionDays) {
    this.store = store;
    this.meterRegistry = meterRegistry;
    this.retentionDays = retentionDays;
  }

  @Transactional
  public int archive(LocalDate today) {
    LocalDate cutoff = today.minusDays(retentionDays);
    int archived = store.archiveBefore(cutoff);
    if (archived > 0) {
      meterRegistry.counter("awap.usage.archived").increment(archived);
    }
    LOGGER.info("Usage retention pass archived {} counter rows (cutoff {}).", archived, cutoff);
    return archived;
  }

  public long retentionDays() {
    return retentionDays;
  }
}
