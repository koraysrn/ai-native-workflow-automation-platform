package com.awap.analytics.infrastructure.opensearch;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the hardened OpenSearch analytics sink. Every value has a safe production
 * default so the application still starts when operators only set the endpoint.
 */
@ConfigurationProperties(prefix = "awap.analytics.opensearch")
public record OpenSearchProperties(
    boolean enabled,
    String endpoint,
    String index,
    String dlqIndex,
    int queueCapacity,
    int batchSize,
    long flushIntervalMs,
    List<Long> retryDelaysMs,
    int maxAttempts,
    int breakerFailureThreshold,
    long breakerOpenMs,
    long connectTimeoutMs,
    long requestTimeoutMs,
    String ismPolicyId,
    String indexTemplateName,
    String dlqIndexTemplateName,
    Ilm ilm) {

  /** ILM/ISM retention windows. Deletion happens at {@code deleteDays} total index age. */
  public record Ilm(int hotDays, int warmDays, int deleteDays) {

    public Ilm {
      hotDays = hotDays > 0 ? hotDays : 7;
      warmDays = warmDays > 0 ? warmDays : 30;
      deleteDays = deleteDays > hotDays ? deleteDays : 180;
    }

    public String description() {
      return "AWAP analytics retention: hot "
          + hotDays
          + "d, warm "
          + warmDays
          + "d, delete "
          + deleteDays
          + "d";
    }
  }

  public OpenSearchProperties {
    endpoint = blankToDefault(endpoint, "http://localhost:9200");
    index = blankToDefault(index, "awap-analytics");
    dlqIndex = blankToDefault(dlqIndex, index + "-dlq");
    queueCapacity = queueCapacity > 0 ? queueCapacity : 1_000;
    batchSize = batchSize > 0 ? batchSize : 100;
    flushIntervalMs = flushIntervalMs > 0 ? flushIntervalMs : 1_000L;
    retryDelaysMs =
        retryDelaysMs == null || retryDelaysMs.isEmpty()
            ? List.of(1_000L, 5_000L, 25_000L)
            : List.copyOf(retryDelaysMs);
    maxAttempts = maxAttempts > 0 ? maxAttempts : 4;
    breakerFailureThreshold = breakerFailureThreshold > 0 ? breakerFailureThreshold : 5;
    breakerOpenMs = breakerOpenMs > 0 ? breakerOpenMs : 30_000L;
    connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 2_000L;
    requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : 3_000L;
    ismPolicyId = blankToDefault(ismPolicyId, "awap-analytics-ism");
    indexTemplateName = blankToDefault(indexTemplateName, "awap-analytics-template");
    dlqIndexTemplateName = blankToDefault(dlqIndexTemplateName, "awap-analytics-dlq-template");
    ilm = ilm == null ? new Ilm(7, 30, 180) : ilm;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
