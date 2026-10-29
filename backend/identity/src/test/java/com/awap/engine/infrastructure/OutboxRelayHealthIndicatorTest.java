package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxRelayConfigurationGuard;
import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Status mapping and details of the actuator health contributor (issue #122). */
class OutboxRelayHealthIndicatorTest {

  private final OutboxRelayConfigurationGuard guard = mock(OutboxRelayConfigurationGuard.class);
  private final OutboxRelayStatus status = new OutboxRelayStatus();

  private OutboxRelayHealthIndicator indicator() {
    when(guard.datasourceName()).thenReturn("awap-outbox-relay");
    return new OutboxRelayHealthIndicator(status, guard);
  }

  @Test
  void reportsUnknownUntilTheRelayHasRun() {
    when(guard.mode()).thenReturn(OutboxRelayMode.JDBC);

    Health health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails())
        .containsEntry("mode", "jdbc")
        .containsEntry("rlsSafe", true)
        .containsEntry("relayDatasource", "awap-outbox-relay")
        .containsEntry("lastSuccessfulPublish", "none")
        .containsEntry("consecutiveFailures", 0);
  }

  @Test
  void reportsUpAfterAPublishAndExposesTheTimestamp() {
    when(guard.mode()).thenReturn(OutboxRelayMode.JDBC);
    OutboxRelayHealthIndicator indicator = indicator();
    status.recordSuccess(2);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails().get("lastSuccessfulPublish")).isNotEqualTo("none");
    assertThat(health.getDetails()).containsEntry("publishedEvents", 2L);
    assertThat(health.getDetails()).doesNotContainKey("lastFailureReason");
  }

  @Test
  void reportsDownWhenPublishingFails() {
    when(guard.mode()).thenReturn(OutboxRelayMode.JDBC);
    OutboxRelayHealthIndicator indicator = indicator();
    status.recordSuccess(1);
    status.recordFailure("send_failed", 0);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("consecutiveFailures", 1)
        .containsEntry("lastFailureReason", "send_failed");
  }

  @Test
  void reportsTheJpaFallbackAsNotRlsSafeWithAWarning() {
    when(guard.mode()).thenReturn(OutboxRelayMode.JPA);
    OutboxRelayHealthIndicator indicator = indicator();
    status.recordFailure("claim_failed", 0);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("mode", "jpa")
        .containsEntry("rlsSafe", false)
        .containsKey("warning");
  }
}
