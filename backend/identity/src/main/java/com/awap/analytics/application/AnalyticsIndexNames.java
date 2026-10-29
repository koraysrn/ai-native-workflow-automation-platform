package com.awap.analytics.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Resolves daily OpenSearch index names for raw analytics events and their dead-letter companion.
 * Daily indices keep retention windows small and let ISM/ILM manage ageing per day.
 */
public final class AnalyticsIndexNames {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

  private final String indexPrefix;
  private final String deadLetterPrefix;

  public AnalyticsIndexNames(String indexPrefix, String deadLetterPrefix) {
    this.indexPrefix = indexPrefix;
    this.deadLetterPrefix = deadLetterPrefix;
  }

  public String dailyIndex(Instant timestamp) {
    return indexPrefix + "-" + DATE.format(timestamp);
  }

  public String deadLetterIndex(Instant timestamp) {
    return deadLetterPrefix + "-" + DATE.format(timestamp);
  }

  public String indexPrefix() {
    return indexPrefix;
  }

  public String deadLetterPrefix() {
    return deadLetterPrefix;
  }

  public String indexPattern() {
    return indexPrefix + "-*";
  }

  public String deadLetterPattern() {
    return deadLetterPrefix + "-*";
  }
}
