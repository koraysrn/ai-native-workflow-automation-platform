package com.awap.usage.domain;

/**
 * Bounded set of metered tenant resources. The enum (rather than free-form keys) is what keeps the
 * Prometheus label cardinality under control and prevents arbitrary metrics from entering the
 * model.
 */
public enum UsageMetric {
  WORKFLOW_EXECUTION("workflow_executions", Unit.COUNT, 1_000L),
  TASK_DECISION("task_decisions", Unit.COUNT, 10_000L),
  AI_CALL("ai_calls", Unit.COUNT, 1_000L),
  DOCUMENT_BYTES("document_bytes", Unit.BYTES, 5L * 1024 * 1024 * 1024),
  ACTIVE_USER("active_users", Unit.COUNT, 100L),
  API_REQUEST("api_requests", Unit.COUNT, 1_000_000L);

  /** Sentinel limit meaning "no daily cap". */
  public static final long UNLIMITED = -1L;

  private final String key;
  private final Unit unit;
  private final long defaultDailyLimit;

  UsageMetric(String key, Unit unit, long defaultDailyLimit) {
    this.key = key;
    this.unit = unit;
    this.defaultDailyLimit = defaultDailyLimit;
  }

  public static UsageMetric fromKey(String key) {
    for (UsageMetric metric : values()) {
      if (metric.key.equals(key)) {
        return metric;
      }
    }
    throw new IllegalArgumentException("Unknown usage metric: " + key);
  }

  public String key() {
    return key;
  }

  public Unit unit() {
    return unit;
  }

  public long defaultDailyLimit() {
    return defaultDailyLimit;
  }

  /** Measurement unit of a metric; reflected in the API contract. */
  public enum Unit {
    COUNT,
    BYTES
  }
}
