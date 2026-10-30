package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** State-machine and optimistic-lock invariants of the saga aggregate. */
class SagaDomainTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
  private static final Instant LATER = NOW.plusSeconds(30);

  @Test
  void sagaCompletesOnlyFromRunningAndIsIdempotent() {
    SagaInstance saga = saga();

    saga.markCompleted(NOW);
    saga.markCompleted(LATER);

    assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    assertThat(saga.getCompensationCompletedAt()).isEqualTo(NOW);
    assertThat(SagaStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(SagaStatus.RUNNING.isTerminal()).isFalse();
  }

  @Test
  void sagaCanCompensateFromRunningOrCompleted() {
    SagaInstance running = saga();
    running.beginCompensation("failure", NOW);
    assertThat(running.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
    assertThat(running.getCompensationStartedAt()).isEqualTo(NOW);
    assertThat(running.getNextCompensationAt()).isEqualTo(NOW);

    SagaInstance completed = saga();
    completed.markCompleted(NOW);
    completed.beginCompensation("parent failure", LATER);
    assertThat(completed.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
    assertThat(completed.getCompensationStartedAt()).isEqualTo(LATER);
  }

  @Test
  void repeatedCompensationKeepsTheOriginalStartInstant() {
    SagaInstance saga = saga();
    saga.beginCompensation("first", NOW);
    saga.beginCompensation("second", LATER);

    assertThat(saga.getFailureReason()).isEqualTo("second");
    assertThat(saga.getCompensationStartedAt()).isEqualTo(NOW);
  }

  @Test
  void terminalSagaCannotStartCompensation() {
    SagaInstance compensated = saga();
    compensated.beginCompensation("failure", NOW);
    compensated.markCompensated(NOW);

    assertThatThrownBy(() -> compensated.beginCompensation("failure", LATER))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void compensationScheduleAndLeaseAreGuardedByStatus() {
    SagaInstance saga = saga();

    assertThatThrownBy(() -> saga.scheduleCompensation(LATER, NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> saga.claim(UUID.randomUUID(), LATER, NOW))
        .isInstanceOf(IllegalStateException.class);

    saga.beginCompensation("failure", NOW);
    UUID lease = UUID.randomUUID();
    saga.scheduleCompensation(LATER, NOW);
    assertThat(saga.getNextCompensationAt()).isEqualTo(LATER);
    saga.claim(lease, LATER, NOW);
    assertThat(saga.getLeaseToken()).isEqualTo(lease);
    assertThat(saga.getLeaseUntil()).isEqualTo(LATER);

    saga.releaseLease(LATER);
    assertThat(saga.getLeaseToken()).isNull();
    assertThat(saga.getLeaseUntil()).isNull();
  }

  @Test
  void compensatedAndFailedSagasClearTheirRetryClock() {
    SagaInstance compensated = saga();
    compensated.beginCompensation("failure", NOW);
    compensated.claim(UUID.randomUUID(), LATER, NOW);
    compensated.markCompensated(LATER);
    assertThat(compensated.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(compensated.getNextCompensationAt()).isNull();
    assertThat(compensated.getLeaseToken()).isNull();
    assertThat(compensated.getCompensationCompletedAt()).isEqualTo(LATER);

    SagaInstance failed = saga();
    failed.beginCompensation("failure", NOW);
    failed.markFailed("exhausted", LATER);
    assertThat(failed.getStatus()).isEqualTo(SagaStatus.FAILED);
    assertThat(failed.getFailureReason()).isEqualTo("exhausted");
    assertThat(failed.getNextCompensationAt()).isNull();
    assertThat(SagaStatus.FAILED.isTerminal()).isTrue();
  }

  @Test
  void failedSagaCanBeReopenedForManualIntervention() {
    SagaInstance saga = saga();
    saga.beginCompensation("failure", NOW);
    saga.markFailed("exhausted", NOW);

    saga.reopenCompensation("manual retry", LATER);

    assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
    assertThat(saga.getFailureReason()).isEqualTo("manual retry");
    assertThat(saga.getNextCompensationAt()).isEqualTo(LATER);
    assertThat(saga.getCompensationCompletedAt()).isNull();

    SagaInstance running = saga();
    assertThatThrownBy(() -> running.reopenCompensation("nope", NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void stepSequencesCompensationAndRetryWithoutLosingTheIdempotencyKey() {
    SagaStep step = step(3, 1000L, 10);

    assertThat(step.getStatus()).isEqualTo(SagaStepStatus.SUCCEEDED);
    assertThat(step.getIdempotencyKey()).startsWith("idem-");
    assertThat(step.getTenantId()).isEqualTo(TENANT);
    assertThat(step.getActionPayload()).isEqualTo("{}");
    assertThat(step.getCreatedAt()).isEqualTo(NOW);
    assertThat(step.getUpdatedAt()).isEqualTo(NOW);
    assertThat(step.getVersion()).isZero();
    assertThat(step.isDue(NOW)).isTrue();

    step.beginCompensation(NOW);
    step.beginCompensation(LATER);
    assertThat(step.getStatus()).isEqualTo(SagaStepStatus.COMPENSATING);
    assertThat(step.getNextAttemptAt()).isEqualTo(NOW);

    step.claim(UUID.randomUUID(), LATER, NOW);
    assertThat(step.getLeaseUntil()).isEqualTo(LATER);
    step.recordCompensationFailure("boom", LATER, NOW);
    assertThat(step.getAttempts()).isEqualTo(1);
    assertThat(step.getLastError()).isEqualTo("boom");
    assertThat(step.getNextAttemptAt()).isEqualTo(LATER);
    assertThat(step.getLeaseToken()).isNull();
    assertThat(step.isDue(NOW)).isFalse();
    assertThat(step.isDue(LATER)).isTrue();

    step.markCompensated(LATER);
    step.markCompensated(NOW);
    assertThat(step.getStatus()).isEqualTo(SagaStepStatus.COMPENSATED);
    assertThat(step.getCompensatedAt()).isEqualTo(LATER);
    assertThat(step.getLastError()).isNull();
    assertThat(step.getNextAttemptAt()).isNull();
    assertThat(SagaStepStatus.COMPENSATED.isCompensated()).isTrue();
  }

  @Test
  void deadLetteredStepCanOnlyBeRetriedManually() {
    SagaStep step = step(1, 100L, 5);
    step.beginCompensation(NOW);
    step.deadLetter("exhausted", NOW);

    assertThat(step.getStatus()).isEqualTo(SagaStepStatus.DLQ);
    assertThat(step.getAttempts()).isEqualTo(1);
    assertThat(step.getNextAttemptAt()).isNull();
    assertThat(SagaStepStatus.DLQ.isDeadLettered()).isTrue();

    step.resetForManualRetry(LATER);
    assertThat(step.getStatus()).isEqualTo(SagaStepStatus.COMPENSATING);
    assertThat(step.getAttempts()).isZero();
    assertThat(step.getLastError()).isNull();
    assertThat(step.getNextAttemptAt()).isEqualTo(LATER);

    SagaStep succeeded = step(1, 100L, 5);
    assertThatThrownBy(() -> succeeded.resetForManualRetry(NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> succeeded.claim(UUID.randomUUID(), LATER, NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  private SagaInstance saga() {
    return new SagaInstance(TENANT, UUID.randomUUID(), "workflow:" + UUID.randomUUID());
  }

  private SagaStep step(int maxAttempts, long retryBackoffMs, int timeoutSeconds) {
    return new SagaStep(
        TENANT,
        UUID.randomUUID(),
        1,
        "erp-step",
        "WEBHOOK",
        "WEBHOOK",
        "{}",
        "WEBHOOK",
        "{\"url\":\"https://erp.example.com/rollback\"}",
        "idem-" + UUID.randomUUID(),
        maxAttempts,
        retryBackoffMs,
        timeoutSeconds,
        NOW);
  }
}
