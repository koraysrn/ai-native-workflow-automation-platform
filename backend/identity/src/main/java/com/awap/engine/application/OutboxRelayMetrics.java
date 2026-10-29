package com.awap.engine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Metrics for the outbox relay path (issue #122).
 *
 * <p>{@code awap.outbox.relay.mode{mode="jdbc"|"jpa"}} is a 1/0 gauge so a dashboard or alert can
 * show which path is live with {@code awap_outbox_relay_mode{mode="jpa"} == 1}. Both series are
 * always registered, so a missing series never hides the fallback.
 *
 * <p>Publish failures are tagged with the same low-cardinality {@code mode} tag plus a {@code
 * reason}; tenant/aggregate identifiers are deliberately absent to keep the series count bounded.
 */
@Component
public class OutboxRelayMetrics {

  public static final String RELAY_MODE = "awap.outbox.relay.mode";
  public static final String PUBLISH_FAILURES = "awap.outbox.publish_failures";
  public static final String MODE_TAG = "mode";
  public static final String REASON_TAG = "reason";
  public static final String REASON_SEND_FAILED = "send_failed";
  public static final String REASON_CLAIM_FAILED = "claim_failed";

  private final Counter sendFailures;
  private final Counter claimFailures;

  @Autowired
  public OutboxRelayMetrics(MeterRegistry registry, OutboxRelayConfigurationGuard guard) {
    this(registry, guard.mode());
  }

  /** Test-friendly constructor that does not need the Spring environment. */
  public OutboxRelayMetrics(MeterRegistry registry, OutboxRelayMode mode) {
    for (OutboxRelayMode candidate : OutboxRelayMode.values()) {
      Gauge.builder(RELAY_MODE, candidate, candidateMode -> candidateMode == mode ? 1d : 0d)
          .tag(MODE_TAG, candidate.tag())
          .description("Active outbox relay path (1 = active, 0 = inactive)")
          .register(registry);
    }
    this.sendFailures = failureCounter(registry, mode, REASON_SEND_FAILED);
    this.claimFailures = failureCounter(registry, mode, REASON_CLAIM_FAILED);
  }

  /** A claimed row could not be handed to Kafka and was released for a retry. */
  public void publishFailure() {
    sendFailures.increment();
  }

  /** The relay could not claim a batch at all (database/pool problem). */
  public void claimFailure() {
    claimFailures.increment();
  }

  private Counter failureCounter(MeterRegistry registry, OutboxRelayMode mode, String reason) {
    return Counter.builder(PUBLISH_FAILURES)
        .tag(MODE_TAG, mode.tag())
        .tag(REASON_TAG, reason)
        .description("Outbox relay publish/claim failures")
        .register(registry);
  }
}
