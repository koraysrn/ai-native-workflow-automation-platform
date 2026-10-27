package com.awap.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UsageMetricTest {

  @Test
  void metricsHaveUniqueStableKeys() {
    Set<String> keys = new HashSet<>();
    for (UsageMetric metric : UsageMetric.values()) {
      assertThat(keys.add(metric.key())).isTrue();
      assertThat(metric.defaultDailyLimit()).isPositive();
    }
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "workflow_executions",
            "task_decisions",
            "ai_calls",
            "document_bytes",
            "active_users",
            "api_requests");
  }

  @Test
  void fromKeyResolvesKnownMetric() {
    assertThat(UsageMetric.fromKey("ai_calls")).isEqualTo(UsageMetric.AI_CALL);
    assertThat(UsageMetric.fromKey("document_bytes").unit()).isEqualTo(UsageMetric.Unit.BYTES);
  }

  @Test
  void fromKeyRejectsUnknownMetric() {
    assertThatThrownBy(() -> UsageMetric.fromKey("tenant.email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown usage metric");
  }

  @Test
  void usageEventRejectsNonPositiveDeltaAndBlankKey() {
    assertThatThrownBy(
            () ->
                new UsageEvent(
                    " ",
                    java.util.UUID.randomUUID(),
                    UsageMetric.AI_CALL,
                    java.time.LocalDate.now(),
                    1))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new UsageEvent(
                    "k",
                    java.util.UUID.randomUUID(),
                    UsageMetric.AI_CALL,
                    java.time.LocalDate.now(),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventKeysAreDeterministicAndDistinctPerAggregate() {
    java.util.UUID id = java.util.UUID.randomUUID();

    assertThat(UsageEventKeys.workflowStarted(id))
        .isEqualTo(UsageEventKeys.workflowStarted(id))
        .isNotEqualTo(UsageEventKeys.taskDecision(id));
    assertThat(UsageEventKeys.aiCall(id)).contains(id.toString());
  }
}
