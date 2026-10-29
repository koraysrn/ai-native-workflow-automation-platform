package com.awap.engine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox relay/reconciliation metrics.
 *
 * <p>Cardinality is bounded on purpose: the only tag is a low-cardinality {@code reason}. Tenant,
 * aggregate and event-type identifiers are deliberately absent so a large tenant cannot blow up the
 * Prometheus series count.
 */
public class OutboxMetrics {

  public static final String STUCK = "awap.outbox.stuck";
  public static final String CLAIM_AGE_SECONDS = "awap.outbox.claim_age_seconds";
  public static final String PUBLISH_FAILURES = "awap.outbox.publish_failures";
  public static final String EXHAUSTED = "awap.outbox.exhausted";
  public static final String RECONCILIATION_FAILURES = "awap.outbox.reconciliation_failures";

  public static final String REASON_TAG = "reason";
  public static final String REASON_STALE_CLAIM = "stale_claim";
  public static final String REASON_LEGACY_ROW = "legacy_published_without_timestamp";
  public static final String REASON_SEND_FAILED = "send_failed";
  public static final String REASON_CORRUPTED_PAYLOAD = "corrupted_payload";
  public static final String REASON_CLAIM_LOST = "claim_lost";

  private final MeterRegistry registry;
  private final DistributionSummary claimAge;
  private final AtomicLong exhausted = new AtomicLong();
  private final AtomicLong reconciliationFailures = new AtomicLong();

  public OutboxMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.claimAge =
        DistributionSummary.builder(CLAIM_AGE_SECONDS)
            .description("Age of an outbox claim when it is released or completed")
            .baseUnit("seconds")
            .register(registry);
    Gauge.builder(EXHAUSTED, exhausted, AtomicLong::doubleValue)
        .description("Outbox rows that exhausted their publish attempts and need operator action")
        .register(registry);
    Gauge.builder(RECONCILIATION_FAILURES, reconciliationFailures, AtomicLong::doubleValue)
        .description("Consecutive outbox reconciliation runs that failed")
        .register(registry);
  }

  /** One row was re-queued because its lease expired while it was still unpublished. */
  public void staleClaimReleased(double claimAgeSeconds) {
    counter(STUCK, REASON_STALE_CLAIM).increment();
    claimAge.record(Math.max(0d, claimAgeSeconds));
  }

  /** Legacy/partial rows ({@code published = true}, no {@code published_at}) seen in this run. */
  public void legacyRowsReported(int count) {
    if (count > 0) {
      counter(STUCK, REASON_LEGACY_ROW).increment(count);
    }
  }

  /** A publish attempt failed; {@code reason} is one of the low-cardinality constants above. */
  public void publishFailure(String reason) {
    counter(PUBLISH_FAILURES, reason).increment();
  }

  /** Publishes the quarantined-row count observed by the last reconciliation run. */
  public void exhaustedRows(long count) {
    exhausted.set(count);
  }

  /** Records a failed reconciliation run; the counter resets on the next successful run. */
  public void reconciliationFailed() {
    reconciliationFailures.incrementAndGet();
  }

  /** Records a successful reconciliation run. */
  public void reconciliationSucceeded() {
    reconciliationFailures.set(0);
  }

  private Counter counter(String name, String reason) {
    return Counter.builder(name)
        .tag(REASON_TAG, reason)
        .description("Outbox relay and reconciliation events")
        .register(registry);
  }
}
