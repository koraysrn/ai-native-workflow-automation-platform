package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxReconciliationSchedulerTest {

  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int MAX_ATTEMPTS = 5;
  private static final int BATCH = 1000;

  @Mock private OutboxClaimStore claimStore;

  private SimpleMeterRegistry meterRegistry;
  private OutboxReconciliationScheduler scheduler;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    scheduler =
        new OutboxReconciliationScheduler(
            claimStore, new OutboxMetrics(meterRegistry), LEASE, MAX_ATTEMPTS, BATCH);
  }

  private double stuckCount(String reason) {
    return meterRegistry
        .get(OutboxMetrics.STUCK)
        .tag(OutboxMetrics.REASON_TAG, reason)
        .counter()
        .count();
  }

  @Test
  void releasesStaleClaimsAndReportsOneMetricPerRow() {
    UUID tenant = UUID.randomUUID();
    when(claimStore.releaseStaleClaims(LEASE, BATCH))
        .thenReturn(
            List.of(
                new OutboxClaimStore.ReleasedOutboxClaim(UUID.randomUUID(), tenant, 125.0d),
                new OutboxClaimStore.ReleasedOutboxClaim(UUID.randomUUID(), tenant, 90.0d)));
    when(claimStore.findLegacyPublishedRows(BATCH)).thenReturn(List.of());
    when(claimStore.countExhausted(MAX_ATTEMPTS)).thenReturn(0L);

    scheduler.reconcile();

    assertThat(stuckCount(OutboxMetrics.REASON_STALE_CLAIM)).isEqualTo(2.0d);
    assertThat(meterRegistry.get(OutboxMetrics.CLAIM_AGE_SECONDS).summary().count()).isEqualTo(2L);
    assertThat(meterRegistry.get(OutboxMetrics.CLAIM_AGE_SECONDS).summary().max())
        .isEqualTo(125.0d);
    // Reconciliation re-queues rows; it never publishes them itself.
    verify(claimStore, never()).claimBatch(anyInt(), any(), anyInt());
    verify(claimStore, never()).markPublished(any(), any());
  }

  @Test
  void reportsLegacyRowsWithoutRepublishingThem() {
    UUID legacyId = UUID.randomUUID();
    when(claimStore.releaseStaleClaims(LEASE, BATCH)).thenReturn(List.of());
    when(claimStore.findLegacyPublishedRows(BATCH))
        .thenReturn(
            List.of(
                new OutboxClaimStore.LegacyOutboxRow(
                    legacyId, UUID.randomUUID(), "TASK_COMPLETED", Instant.now(), 0)));
    when(claimStore.countExhausted(MAX_ATTEMPTS)).thenReturn(0L);

    scheduler.reconcile();

    assertThat(stuckCount(OutboxMetrics.REASON_LEGACY_ROW)).isEqualTo(1.0d);
    verify(claimStore, never()).markPublished(eq(legacyId), any());
    verify(claimStore, never()).release(eq(legacyId), any());
    verify(claimStore, never()).claimBatch(anyInt(), any(), anyInt());
  }

  @Test
  void reportsExhaustedRowsAsAGauge() {
    when(claimStore.releaseStaleClaims(LEASE, BATCH)).thenReturn(List.of());
    when(claimStore.findLegacyPublishedRows(BATCH)).thenReturn(List.of());
    when(claimStore.countExhausted(MAX_ATTEMPTS)).thenReturn(7L);

    scheduler.reconcile();

    assertThat(meterRegistry.get(OutboxMetrics.EXHAUSTED).gauge().value()).isEqualTo(7.0d);
    assertThat(meterRegistry.find(OutboxMetrics.STUCK).counters()).isEmpty();
  }

  @Test
  void schedulerCrashDoesNotStopTheNextRun() {
    UUID tenant = UUID.randomUUID();
    when(claimStore.releaseStaleClaims(LEASE, BATCH))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(
            List.of(new OutboxClaimStore.ReleasedOutboxClaim(UUID.randomUUID(), tenant, 61.0d)));
    when(claimStore.findLegacyPublishedRows(BATCH)).thenReturn(List.of());
    when(claimStore.countExhausted(MAX_ATTEMPTS)).thenReturn(0L);

    assertThatCode(scheduler::reconcile).doesNotThrowAnyException();
    assertThat(meterRegistry.get(OutboxMetrics.RECONCILIATION_FAILURES).gauge().value())
        .isEqualTo(1.0d);

    scheduler.reconcile();

    assertThat(stuckCount(OutboxMetrics.REASON_STALE_CLAIM)).isEqualTo(1.0d);
    assertThat(meterRegistry.get(OutboxMetrics.RECONCILIATION_FAILURES).gauge().value()).isZero();
  }

  @Test
  void healthyRunRecordsNoLegacyOrStuckSeries() {
    when(claimStore.releaseStaleClaims(LEASE, BATCH)).thenReturn(List.of());
    when(claimStore.findLegacyPublishedRows(BATCH)).thenReturn(List.of());
    when(claimStore.countExhausted(MAX_ATTEMPTS)).thenReturn(0L);

    scheduler.reconcile();

    assertThat(meterRegistry.find(OutboxMetrics.STUCK).counters()).isEmpty();
    assertThat(meterRegistry.get(OutboxMetrics.EXHAUSTED).gauge().value()).isZero();
  }
}
