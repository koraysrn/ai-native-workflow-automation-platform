package com.awap.engine.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Last known outcome of the outbox relay runs (issue #122).
 *
 * <p>The relay used to fail invisibly: a send error only produced a log line, so a relay that had
 * stopped publishing looked exactly like a healthy one. This component is the single place where
 * each run records its outcome, and the actuator health indicator reads it. It is deliberately
 * in-memory and per-instance: an instance that has not published yet reports {@code UNKNOWN}
 * instead of pretending to be {@code UP}.
 */
@Component
public class OutboxRelayStatus {

  private final Clock clock;

  private Optional<Instant> lastRunAt = Optional.empty();
  private Optional<Instant> lastSuccessfulPublishAt = Optional.empty();
  private Optional<Instant> lastFailureAt = Optional.empty();
  private Optional<String> lastFailureReason = Optional.empty();
  private int consecutiveFailures;
  private long publishedEvents;

  public OutboxRelayStatus() {
    this(Clock.systemUTC());
  }

  /** Visible for tests that need deterministic timestamps. */
  OutboxRelayStatus(Clock clock) {
    this.clock = clock;
  }

  /**
   * Records a run that finished without a single publish failure. {@code publishedCount} is the
   * number of rows this run actually published (zero for an empty poll, which is still a healthy
   * sign that claiming works).
   */
  public synchronized void recordSuccess(int publishedCount) {
    Instant now = clock.instant();
    lastRunAt = Optional.of(now);
    consecutiveFailures = 0;
    recordPublished(now, publishedCount);
  }

  /** Records a run in which at least one row could not be published. */
  public synchronized void recordFailure(String reason, int publishedCount) {
    Instant now = clock.instant();
    lastRunAt = Optional.of(now);
    lastFailureAt = Optional.of(now);
    lastFailureReason = Optional.ofNullable(reason);
    consecutiveFailures = consecutiveFailures + 1;
    recordPublished(now, publishedCount);
  }

  private void recordPublished(Instant now, int publishedCount) {
    if (publishedCount > 0) {
      lastSuccessfulPublishAt = Optional.of(now);
      publishedEvents += publishedCount;
    }
  }

  /** Immutable view consumed by the health indicator (no locking leaks to callers). */
  public synchronized Snapshot snapshot() {
    return new Snapshot(
        lastRunAt,
        lastSuccessfulPublishAt,
        lastFailureAt,
        lastFailureReason,
        consecutiveFailures,
        publishedEvents);
  }

  /** Point-in-time relay outcome. */
  public record Snapshot(
      Optional<Instant> lastRunAt,
      Optional<Instant> lastSuccessfulPublishAt,
      Optional<Instant> lastFailureAt,
      Optional<String> lastFailureReason,
      int consecutiveFailures,
      long publishedEvents) {

    /** Whether the relay completed at least one run since startup. */
    public boolean hasRun() {
      return lastRunAt.isPresent();
    }

    /** Healthy means "ran at least once and the last runs did not fail". */
    public boolean healthy() {
      return hasRun() && consecutiveFailures == 0;
    }
  }
}
