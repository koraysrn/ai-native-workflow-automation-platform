package com.awap.engine.application;

import com.awap.engine.domain.ReplayDivergence;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Micrometer metrics and the alert hook for replay runs, divergences and limit rejections. */
@Component
public class ReplayMetrics {

  public static final String RUNS = "awap.replay.runs";
  public static final String DIVERGENCES = "awap.replay.divergences";
  public static final String DURATION = "awap.replay.duration";
  public static final String LIMITS = "awap.replay.limits";

  private static final Logger LOGGER = LoggerFactory.getLogger(ReplayMetrics.class);

  private final MeterRegistry meterRegistry;

  public ReplayMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Records one replay run and every divergence it produced. */
  public void recordRun(
      String result, boolean dryRun, Duration duration, List<ReplayDivergence> divergences) {
    String dryRunTag = Boolean.toString(dryRun);
    meterRegistry.counter(RUNS, "result", result, "dry_run", dryRunTag).increment();
    meterRegistry.timer(DURATION, "dry_run", dryRunTag).record(duration);
    for (ReplayDivergence divergence : divergences) {
      meterRegistry
          .counter(DIVERGENCES, "type", divergence.type().name(), "dry_run", dryRunTag)
          .increment();
    }
    if (!divergences.isEmpty()) {
      LOGGER.error(
          "Replay divergence detected (dryRun={}, count={}): {}",
          dryRun,
          divergences.size(),
          divergences.stream().map(divergence -> divergence.type().name()).distinct().toList());
    }
  }

  /** Records a controlled rejection (row cap, timeout, concurrent replay, optimistic lock). */
  public void recordLimit(String reason) {
    meterRegistry.counter(LIMITS, "reason", reason).increment();
    LOGGER.warn("Replay rejected: reason={}", reason);
  }
}
