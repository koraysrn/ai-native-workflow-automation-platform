package com.awap.engine.infrastructure;

import com.awap.engine.application.OutboxRelayConfigurationGuard;
import com.awap.engine.application.OutboxRelayMode;
import com.awap.engine.application.OutboxRelaySource;
import com.awap.engine.application.OutboxRelayStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health contributor for the outbox relay (issue #122).
 *
 * <p>Answers the question "is the event pipeline actually moving?":
 *
 * <ul>
 *   <li>{@code UNKNOWN} until the relay completed at least one run (a freshly started instance has
 *       not proven anything yet),
 *   <li>{@code UP} while the last runs published without a failure,
 *   <li>{@code DOWN} as soon as a claim or a Kafka send fails, so the failure is visible in {@code
 *       /actuator/health} instead of only in a log line. A successful run clears it.
 * </ul>
 *
 * <p>Details always expose the active {@code mode} ({@code jdbc}/{@code jpa}), the last successful
 * publish timestamp and whether that path is RLS safe.
 */
@Component
public class OutboxRelayHealthIndicator implements HealthIndicator {

  private static final String NEVER = "none";

  private final OutboxRelaySource source;
  private final OutboxRelayStatus status;
  private final OutboxRelayConfigurationGuard guard;

  public OutboxRelayHealthIndicator(
      OutboxRelaySource source, OutboxRelayStatus status, OutboxRelayConfigurationGuard guard) {
    this.source = source;
    this.status = status;
    this.guard = guard;
  }

  @Override
  public Health health() {
    OutboxRelayStatus.Snapshot snapshot = status.snapshot();
    Health.Builder builder = builder(snapshot);
    OutboxRelayMode mode = source.mode();
    builder
        .withDetail("mode", mode.tag())
        .withDetail("relayDatasource", guard.datasourceName())
        .withDetail("rlsSafe", mode.rlsSafe())
        .withDetail("lastSuccessfulPublish", timestamp(snapshot.lastSuccessfulPublishAt()))
        .withDetail("lastRun", timestamp(snapshot.lastRunAt()))
        .withDetail("publishedEvents", snapshot.publishedEvents())
        .withDetail("consecutiveFailures", snapshot.consecutiveFailures());
    snapshot
        .lastFailureReason()
        .ifPresent(reason -> builder.withDetail("lastFailureReason", reason));
    snapshot
        .lastFailureAt()
        .ifPresent(failedAt -> builder.withDetail("lastFailure", failedAt.toString()));
    if (!mode.rlsSafe()) {
      builder.withDetail(
          "warning",
          "JPA fallback without tenant context: PostgreSQL RLS hides every outbox row; dev/test"
              + " only (runbook O8).");
    }
    return builder.build();
  }

  private static Health.Builder builder(OutboxRelayStatus.Snapshot snapshot) {
    if (snapshot.consecutiveFailures() > 0) {
      return Health.down();
    }
    if (!snapshot.hasRun()) {
      return Health.unknown();
    }
    return Health.up();
  }

  private static String timestamp(Optional<Instant> value) {
    return value.map(Instant::toString).orElse(NEVER);
  }
}
