package com.awap.engine.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded-resource settings for the replay engine. Defaults keep a single replay cheap while still
 * allowing very large instances to be handled by explicitly raising the limits.
 *
 * @param maxRows hard cap on the number of execution-log rows folded in one replay (fail closed
 *     above it instead of risking an OOM)
 * @param pageSize rows fetched per keyset page; only this page is held in memory at a time
 * @param timeout wall-clock budget for one projection
 */
@ConfigurationProperties(prefix = "awap.replay")
public record ReplaySettings(
    @DefaultValue("100000") int maxRows,
    @DefaultValue("500") int pageSize,
    @DefaultValue("30s") Duration timeout) {

  public ReplaySettings {
    if (maxRows <= 0) {
      throw new IllegalArgumentException("awap.replay.max-rows must be positive");
    }
    if (pageSize <= 0) {
      throw new IllegalArgumentException("awap.replay.page-size must be positive");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("awap.replay.timeout must be positive");
    }
  }

  public static ReplaySettings defaults() {
    return new ReplaySettings(100_000, 500, Duration.ofSeconds(30));
  }
}
