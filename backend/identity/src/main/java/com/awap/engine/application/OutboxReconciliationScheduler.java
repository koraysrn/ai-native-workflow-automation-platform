package com.awap.engine.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic safety net for the outbox crash window (#121).
 *
 * <p>Every run:
 *
 * <ol>
 *   <li>releases claims older than the lease, so rows abandoned by a dead relay become claimable
 *       again and are republished exactly once by the next relay run,
 *   <li>reports legacy rows ({@code published = true} without {@code published_at}) that the V37
 *       relay could have stranded; they are never republished automatically because a blind replay
 *       may duplicate a business transaction that did reach Kafka, and
 *   <li>reports the number of rows that exhausted their publish attempts (quarantined, needs an
 *       operator).
 * </ol>
 *
 * <p>Failures are logged and swallowed: one bad run must never disable the schedule, otherwise a
 * transient database error would silently stop reconciliation for the lifetime of the process.
 */
@Component
public class OutboxReconciliationScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxReconciliationScheduler.class);

  private final OutboxClaimStore claimStore;
  private final OutboxMetrics metrics;
  private final Duration claimLease;
  private final int maxPublishAttempts;
  private final int batchSize;

  @Autowired
  public OutboxReconciliationScheduler(
      OutboxClaimStore claimStore,
      MeterRegistry meterRegistry,
      @Value("${awap.outbox.claim-lease:60s}") Duration claimLease,
      @Value("${awap.outbox.max-publish-attempts:5}") int maxPublishAttempts,
      @Value("${awap.outbox.reconciliation.batch-size:1000}") int batchSize) {
    this(claimStore, new OutboxMetrics(meterRegistry), claimLease, maxPublishAttempts, batchSize);
  }

  /** Test-friendly constructor that reuses an already-built metrics facade. */
  public OutboxReconciliationScheduler(
      OutboxClaimStore claimStore,
      OutboxMetrics metrics,
      Duration claimLease,
      int maxPublishAttempts,
      int batchSize) {
    this.claimStore = claimStore;
    this.metrics = metrics;
    this.claimLease = claimLease;
    this.maxPublishAttempts = Math.max(1, maxPublishAttempts);
    this.batchSize = Math.max(1, batchSize);
  }

  @Scheduled(fixedDelayString = "${awap.outbox.reconciliation.interval-ms:60000}")
  public void reconcile() {
    try {
      releaseStaleClaims();
      reportLegacyRows();
      metrics.exhaustedRows(claimStore.countExhausted(maxPublishAttempts));
      metrics.reconciliationSucceeded();
    } catch (RuntimeException ex) {
      metrics.reconciliationFailed();
      log.error("Outbox reconciliation run failed; the next scheduled run will retry", ex);
    }
  }

  private void releaseStaleClaims() {
    List<OutboxClaimStore.ReleasedOutboxClaim> released =
        claimStore.releaseStaleClaims(claimLease, batchSize);
    for (OutboxClaimStore.ReleasedOutboxClaim claim : released) {
      metrics.staleClaimReleased(claim.claimAgeSeconds());
      log.warn(
          "Released stale outbox claim {} for tenant {} after {}s; it is re-queued for republication",
          claim.id(),
          claim.tenantId(),
          Math.round(claim.claimAgeSeconds()));
    }
  }

  private void reportLegacyRows() {
    List<OutboxClaimStore.LegacyOutboxRow> legacy = claimStore.findLegacyPublishedRows(batchSize);
    if (legacy.isEmpty()) {
      return;
    }
    metrics.legacyRowsReported(legacy.size());
    log.error(
        "Found {} legacy outbox rows marked published without published_at (first id: {}); "
            + "they are NOT republished automatically, follow the O7 runbook",
        legacy.size(),
        legacy.get(0).id());
  }
}
