package com.awap.usage.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One metered increment. The event key is the idempotency key persisted in {@code usage_events};
 * {@code day} is always a UTC calendar day so reports and quotas share a single timezone.
 */
public record UsageEvent(
    String eventKey, UUID tenantId, UsageMetric metric, LocalDate day, long delta) {

  public UsageEvent {
    if (eventKey == null || eventKey.isBlank()) {
      throw new IllegalArgumentException("eventKey must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (metric == null) {
      throw new IllegalArgumentException("metric must not be null");
    }
    if (day == null) {
      throw new IllegalArgumentException("day must not be null");
    }
    if (delta <= 0) {
      throw new IllegalArgumentException("delta must be positive");
    }
  }
}
