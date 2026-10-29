package com.awap.engine.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Micrometer instruments for node timeouts.
 *
 * <ul>
 *   <li>{@code awap.node.timeout} — timeouts, tagged with {@code type}, {@code enforcement} and
 *       {@code reason} ({@code enforced}, {@code best_effort} or {@code database}).
 *   <li>{@code awap.node.duration} — elapsed time of every node execution, tagged with {@code
 *       type}.
 *   <li>{@code awap.node.timeout.failures} — timeouts that ended in a terminal node failure.
 *   <li>{@code awap.node.timeout.rejected} — work rejected by the saturated bounded executor.
 *   <li>{@code awap.node.timeout.abandoned} — workers that ignored the interrupt and are still
 *       running; a non-zero value is the thread-leak alert.
 * </ul>
 */
@Component
public class NodeTimeoutMetrics {

  public static final String TIMEOUT = "awap.node.timeout";
  public static final String DURATION = "awap.node.duration";
  public static final String TIMEOUT_FAILURES = "awap.node.timeout.failures";
  public static final String REJECTED = "awap.node.timeout.rejected";
  public static final String ABANDONED = "awap.node.timeout.abandoned";

  private static final String UNKNOWN_TYPE = "UNKNOWN";

  private final MeterRegistry meterRegistry;

  public NodeTimeoutMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Records the elapsed time of a node execution; negative values are clamped to zero. */
  public void recordDuration(String nodeType, Duration elapsed) {
    Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
    Timer.builder(DURATION)
        .description("Elapsed time of a workflow node execution.")
        .tag("type", tag(nodeType))
        .register(meterRegistry)
        .record(safe);
  }

  /** Records one timeout breach with its enforcement level and detection reason. */
  public void recordTimeout(String nodeType, NodeTimeoutEnforcement enforcement, String reason) {
    meterRegistry
        .counter(
            TIMEOUT,
            "type",
            tag(nodeType),
            "enforcement",
            enforcement == null ? NodeTimeoutEnforcement.UNSUPPORTED.name() : enforcement.name(),
            "reason",
            reason == null ? "unknown" : reason)
        .increment();
  }

  /** Records a timeout that exhausted the retry budget and failed the node terminally. */
  public void recordTimeoutFailure(String nodeType) {
    meterRegistry.counter(TIMEOUT_FAILURES, "type", tag(nodeType)).increment();
  }

  /** Records a submission rejected by the bounded executor. */
  public void recordRejected(String nodeType) {
    meterRegistry.counter(REJECTED, "type", tag(nodeType)).increment();
  }

  /** Publishes the number of workers that swallowed their interrupt and are still running. */
  public void registerAbandonedWorkers(AtomicInteger abandonedWorkers) {
    Gauge.builder(ABANDONED, abandonedWorkers, AtomicInteger::get)
        .description("Node timeout workers that ignored the interrupt and are still running.")
        .register(meterRegistry);
  }

  private static String tag(String nodeType) {
    return nodeType == null || nodeType.isBlank() ? UNKNOWN_TYPE : nodeType;
  }
}
