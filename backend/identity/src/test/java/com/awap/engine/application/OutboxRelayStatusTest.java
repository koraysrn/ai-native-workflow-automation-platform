package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** State machine of the relay outcome consumed by the health indicator (issue #122). */
class OutboxRelayStatusTest {

  private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

  private final OutboxRelayStatus status = new OutboxRelayStatus(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aFreshStatusHasNotRunAndIsNotHealthy() {
    OutboxRelayStatus.Snapshot snapshot = status.snapshot();

    assertThat(snapshot.hasRun()).isFalse();
    assertThat(snapshot.healthy()).isFalse();
    assertThat(snapshot.lastRunAt()).isEmpty();
    assertThat(snapshot.lastSuccessfulPublishAt()).isEmpty();
    assertThat(snapshot.publishedEvents()).isZero();
  }

  @Test
  void aSuccessfulPublishingRunRecordsTheRunAndThePublishTimestamp() {
    status.recordSuccess(3);

    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.hasRun()).isTrue();
    assertThat(snapshot.healthy()).isTrue();
    assertThat(snapshot.lastRunAt()).contains(NOW);
    assertThat(snapshot.lastSuccessfulPublishAt()).contains(NOW);
    assertThat(snapshot.publishedEvents()).isEqualTo(3L);
  }

  @Test
  void anEmptyPollIsStillAHealthyRunWithoutAPublishTimestamp() {
    status.recordSuccess(0);

    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.healthy()).isTrue();
    assertThat(snapshot.lastSuccessfulPublishAt()).isEmpty();
  }

  @Test
  void aFailureKeepsTheLastSuccessfulPublishAndIncrementsTheFailureCount() {
    status.recordSuccess(1);
    status.recordFailure("send_failed", 0);

    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.healthy()).isFalse();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(1);
    assertThat(snapshot.lastFailureReason()).contains("send_failed");
    assertThat(snapshot.lastFailureAt()).contains(NOW);
    assertThat(snapshot.lastSuccessfulPublishAt()).contains(NOW);
  }

  @Test
  void consecutiveFailuresAccumulateUntilASuccessClearsThem() {
    status.recordFailure("send_failed", 0);
    status.recordFailure("claim_failed", 0);
    assertThat(status.snapshot().consecutiveFailures()).isEqualTo(2);
    assertThat(status.snapshot().lastFailureReason()).contains("claim_failed");

    status.recordSuccess(0);

    assertThat(status.snapshot().consecutiveFailures()).isZero();
    assertThat(status.snapshot().healthy()).isTrue();
  }

  @Test
  void aFailureWithoutAReasonDoesNotStoreANull() {
    status.recordFailure(null, 0);

    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    assertThat(snapshot.lastFailureReason()).isEmpty();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(1);
  }

  @Test
  void publishedEventsAccumulateAcrossRuns() {
    status.recordSuccess(2);
    status.recordFailure("send_failed", 4);

    assertThat(status.snapshot().publishedEvents()).isEqualTo(6L);
    assertThat(status.snapshot().lastSuccessfulPublishAt()).contains(NOW);
  }
}
