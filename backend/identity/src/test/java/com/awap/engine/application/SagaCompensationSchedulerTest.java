package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Claiming, tenant scoping and poison-row isolation of the compensation recovery scheduler. */
@ExtendWith(MockitoExtension.class)
class SagaCompensationSchedulerTest {

  @Mock private SagaRecoveryRepository recoveryRepository;
  @Mock private SagaService sagaService;
  @Mock private KafkaTenantContext tenantContext;

  private SagaCompensationScheduler scheduler() {
    return new SagaCompensationScheduler(
        recoveryRepository, sagaService, tenantContext, Duration.ofSeconds(30), 25);
  }

  @Test
  void resumesEveryClaimedSagaInsideItsTenantTransaction() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID sagaA = UUID.randomUUID();
    UUID sagaB = UUID.randomUUID();
    when(recoveryRepository.claimDue(any(), any(), any(), eq(25)))
        .thenReturn(
            List.of(
                new SagaRecoveryCandidate(tenantA, sagaA, UUID.randomUUID()),
                new SagaRecoveryCandidate(tenantB, sagaB, UUID.randomUUID())));
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());

    int claimed = scheduler().runOnce(Instant.parse("2026-10-30T10:00:00Z"));

    assertThat(claimed).isEqualTo(2);
    verify(sagaService).resumeCompensation(eq(tenantA), eq(sagaA), any());
    verify(sagaService).resumeCompensation(eq(tenantB), eq(sagaB), any());
  }

  @Test
  void oneBrokenCompensationDoesNotBlockTheRestOfTheBatch() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID sagaA = UUID.randomUUID();
    UUID sagaB = UUID.randomUUID();
    when(recoveryRepository.claimDue(any(), any(), any(), eq(25)))
        .thenReturn(
            List.of(
                new SagaRecoveryCandidate(tenantA, sagaA, UUID.randomUUID()),
                new SagaRecoveryCandidate(tenantB, sagaB, UUID.randomUUID())));
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    doThrow(new IllegalStateException("boom"))
        .when(sagaService)
        .resumeCompensation(eq(tenantA), eq(sagaA), any());

    int claimed = scheduler().runOnce(Instant.parse("2026-10-30T10:00:00Z"));

    assertThat(claimed).isEqualTo(2);
    verify(sagaService).resumeCompensation(eq(tenantB), eq(sagaB), any());
  }

  @Test
  void claimFailureReturnsZeroAndLeavesTheNextTickToRetry() {
    when(recoveryRepository.claimDue(any(), any(), any(), eq(25)))
        .thenThrow(new IllegalStateException("database down"));

    int claimed = scheduler().runOnce(Instant.parse("2026-10-30T10:00:00Z"));

    assertThat(claimed).isZero();
  }

  @Test
  void scheduledTickProcessesAnEmptyClaimBatch() {
    when(recoveryRepository.claimDue(any(), any(), any(), eq(25))).thenReturn(List.of());
    SagaCompensationScheduler scheduler =
        new SagaCompensationScheduler(recoveryRepository, sagaService, tenantContext, 30, 25);

    scheduler.processDueCompensations();

    verify(recoveryRepository).claimDue(any(), any(), any(), eq(25));
  }
}
