package com.awap.usage.application;

import com.awap.usage.domain.UsageEvent;
import com.awap.usage.domain.UsageMetric;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for the usage counter storage. The production adapter executes the atomic
 * PostgreSQL function; unit tests substitute an in-memory implementation with the same semantics.
 */
public interface UsageCounterStore {

  IncrementResult increment(UsageEvent event, long limit);

  List<CounterRow> counters(UUID tenantId, LocalDate from, LocalDate to);

  List<LimitRow> limits(UUID tenantId);

  List<DriftRow> reconcile(LocalDate from, LocalDate to);

  int archiveBefore(LocalDate cutoff);

  /** Result classification of one metered increment. */
  enum Outcome {
    /** The event was new and the counter was increased. */
    COUNTED,
    /** The idempotency key was already present; nothing changed. */
    DUPLICATE,
    /** The increment would cross the daily limit; nothing was counted. */
    QUOTA_EXCEEDED
  }

  /** Outcome plus the counter value after the increment (0 when nothing was counted). */
  record IncrementResult(Outcome outcome, long value) {}

  record CounterRow(LocalDate day, UsageMetric metric, long value) {}

  record LimitRow(UsageMetric metric, long dailyLimit) {}

  record DriftRow(UUID tenantId, LocalDate day, UsageMetric metric, long expected, long actual) {}
}
