package com.awap.usage.application;

import com.awap.usage.application.UsageCounterStore.DriftRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes day-level counters from the raw {@code usage_events} ledger and repairs drift.
 *
 * <p>Every repaired row increments {@code awap.usage.reconciliation.drift{metric=...}} so an alert
 * fires when counters stop matching the source of truth; a clean run is recorded as {@code
 * awap.usage.reconciliation.runs{outcome="clean"}}.
 */
@Service
public class UsageReconciliationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(UsageReconciliationService.class);

  private final UsageCounterStore store;
  private final MeterRegistry meterRegistry;

  public UsageReconciliationService(UsageCounterStore store, MeterRegistry meterRegistry) {
    this.store = store;
    this.meterRegistry = meterRegistry;
  }

  @Transactional
  public int reconcile(LocalDate from, LocalDate to) {
    List<DriftRow> drifts = store.reconcile(from, to);
    for (DriftRow drift : drifts) {
      meterRegistry
          .counter("awap.usage.reconciliation.drift", "metric", drift.metric().key())
          .increment();
      LOGGER.warn(
          "Usage counter drift repaired: tenant={} day={} metric={} expected={} actual={}",
          drift.tenantId(),
          drift.day(),
          drift.metric().key(),
          drift.expected(),
          drift.actual());
    }
    meterRegistry
        .counter(
            "awap.usage.reconciliation.runs", "outcome", drifts.isEmpty() ? "clean" : "repaired")
        .increment();
    return drifts.size();
  }
}
