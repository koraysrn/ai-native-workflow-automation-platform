package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DlqTopicsTest {

  @Test
  void dualPatternMatchesPerTopicAndLegacyTopicsOnly() {
    DlqTopicSubscription subscription =
        new DlqTopicSubscription(true, DlqTopics.LEGACY_TOPIC, DlqTopics.PER_TOPIC_PATTERN);

    assertThat(subscription.matches("awap.dlq")).isTrue();
    assertThat(subscription.matches("workflow.task.events.dlq")).isTrue();
    assertThat(subscription.matches("analytics.events.dlq")).isTrue();

    assertThat(subscription.matches("not-a-dlq-topic")).isFalse();
    assertThat(subscription.matches("workflow.task.events")).isFalse();
    assertThat(subscription.matches("dlq")).isFalse();
    assertThat(subscription.matches("workflow.task.events.dlq.extra")).isFalse();
    assertThat(subscription.matches("workflow.task.events.dlqx")).isFalse();
    assertThat(subscription.matches(null)).isFalse();
  }

  @Test
  void disablingTheLegacyFlagKeepsThePerTopicPathWorking() {
    DlqTopicSubscription subscription =
        new DlqTopicSubscription(false, DlqTopics.LEGACY_TOPIC, DlqTopics.PER_TOPIC_PATTERN);

    assertThat(subscription.legacyEnabled()).isFalse();
    assertThat(subscription.matches("awap.dlq")).isFalse();
    assertThat(subscription.matches("workflow.task.events.dlq")).isTrue();
    assertThat(subscription.topicPattern()).isEqualTo(DlqTopics.PER_TOPIC_PATTERN);
  }

  @Test
  void metricTagIsBoundedToKnownSourceTopics() {
    assertThat(DlqTopics.metricTag("awap.dlq", DlqTopics.DEFAULT_KNOWN_TOPICS))
        .isEqualTo("awap.dlq");
    assertThat(DlqTopics.metricTag("workflow.task.events.dlq", DlqTopics.DEFAULT_KNOWN_TOPICS))
        .isEqualTo("workflow.task.events.dlq");
    assertThat(DlqTopics.metricTag("unexpected.topic.dlq", DlqTopics.DEFAULT_KNOWN_TOPICS))
        .isEqualTo(DlqTopics.OTHER_TAG);
    assertThat(DlqTopics.metricTag("another.unexpected.dlq", DlqTopics.DEFAULT_KNOWN_TOPICS))
        .isEqualTo(DlqTopics.OTHER_TAG);
  }

  @Test
  void copyTargetUsesOriginalTopicHeaderAndFallsBackSafely() {
    assertThat(DlqTopics.copyTarget("analytics.events", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("analytics.events.dlq");
    assertThat(DlqTopics.copyTarget("analytics.events.dlq", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("analytics.events.dlq");
    assertThat(DlqTopics.copyTarget("awap.dlq", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("awap.legacy.dlq");
    assertThat(DlqTopics.copyTarget("bad topic name", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("awap.legacy.dlq");
    assertThat(DlqTopics.copyTarget("", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("awap.legacy.dlq");
    assertThat(DlqTopics.copyTarget(null, "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("awap.legacy.dlq");
    assertThat(DlqTopics.copyTarget("awap", "awap.legacy.dlq", "awap.dlq"))
        .isEqualTo("awap.legacy.dlq");
  }

  @Test
  void subscriptionRejectsBlankOperatorPattern() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new DlqTopicSubscription(true, DlqTopics.LEGACY_TOPIC, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dlq-topic-pattern");
  }
}
