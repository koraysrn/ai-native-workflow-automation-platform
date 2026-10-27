package com.awap.analytics.infrastructure.opensearch;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Shared fixtures for the OpenSearch sink and provisioner tests. */
final class OpenSearchTestSupport {

  private OpenSearchTestSupport() {}

  static OpenSearchProperties properties() {
    return properties(100, 10, List.of(5L, 5L, 5L), 4, 100, 1_000L, 3_600_000L);
  }

  static OpenSearchProperties properties(
      int queueCapacity,
      int batchSize,
      List<Long> retryDelaysMs,
      int maxAttempts,
      int breakerThreshold,
      long breakerOpenMs,
      long flushIntervalMs) {
    return new OpenSearchProperties(
        true,
        "http://localhost:9200",
        "awap-analytics",
        "awap-analytics-dlq",
        queueCapacity,
        batchSize,
        flushIntervalMs,
        retryDelaysMs,
        maxAttempts,
        breakerThreshold,
        breakerOpenMs,
        500L,
        500L,
        "awap-analytics-ism",
        "awap-analytics-template",
        "awap-analytics-dlq-template",
        new OpenSearchProperties.Ilm(7, 30, 180));
  }

  static Map<String, Object> event(
      UUID tenantId, String eventType, UUID taskId, Instant timestamp, long durationMs) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("tenantId", tenantId.toString());
    document.put("eventType", eventType);
    document.put("workflowInstanceId", null);
    document.put("taskId", taskId == null ? null : taskId.toString());
    document.put("durationMs", durationMs);
    document.put("timestamp", timestamp.toString());
    return document;
  }

  static void await(BooleanSupplier condition, long timeoutMs) {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError(ex);
      }
    }
    if (!condition.getAsBoolean()) {
      throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }
  }
}
