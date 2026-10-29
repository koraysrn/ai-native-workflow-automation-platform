package com.awap.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnalyticsIndexNamesTest {

  private final AnalyticsIndexNames names =
      new AnalyticsIndexNames("awap-analytics", "awap-analytics-dlq");

  @Test
  void buildsUtcDailyIndexWithConfiguredPrefix() {
    assertThat(names.dailyIndex(Instant.parse("2026-10-27T23:59:59Z")))
        .isEqualTo("awap-analytics-2026.10.27");
  }

  @Test
  void usesTheUtcDayEvenWhenTheInstantIsInAnotherOffset() {
    assertThat(names.dailyIndex(Instant.parse("2026-10-27T01:30:00+03:00")))
        .isEqualTo("awap-analytics-2026.10.26");
  }

  @Test
  void buildsDeadLetterIndexAndPatterns() {
    assertThat(names.deadLetterIndex(Instant.parse("2026-10-27T10:15:30Z")))
        .isEqualTo("awap-analytics-dlq-2026.10.27");
    assertThat(names.indexPattern()).isEqualTo("awap-analytics-*");
    assertThat(names.deadLetterPattern()).isEqualTo("awap-analytics-dlq-*");
  }
}
