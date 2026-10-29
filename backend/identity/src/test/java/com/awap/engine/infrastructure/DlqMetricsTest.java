package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DlqMetricsTest {

  private SimpleMeterRegistry registry;
  private DlqMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics =
        new DlqMetrics(
            registry,
            "workflow.task.events,workflow.instance.events,analytics.events,document.events");
  }

  @Test
  void legacyMessageIncrementsTopicTaggedAndLegacyCounters() {
    metrics.recordReceived(DlqTopics.LEGACY_TOPIC);

    assertThat(counter("awap.dlq.messages", "topic", "awap.dlq", "legacy", "true").count())
        .isEqualTo(1.0);
    assertThat(counter("awap.dlq.legacy.messages").count()).isEqualTo(1.0);
  }

  @Test
  void perTopicMessageIncrementsItsOwnSeriesAndNotTheLegacyCounter() {
    metrics.recordReceived("workflow.task.events.dlq");
    metrics.recordReceived("workflow.task.events.dlq");

    assertThat(
            counter("awap.dlq.messages", "topic", "workflow.task.events.dlq", "legacy", "false")
                .count())
        .isEqualTo(2.0);
    assertThat(registry.find("awap.dlq.legacy.messages").counter()).isNull();
    assertThat(registry.find("awap.dlq.messages").tag("topic", "awap.dlq").counter()).isNull();
  }

  @Test
  void unexpectedDlqTopicsAreFoldedIntoOneBoundedSeries() {
    metrics.recordReceived("unexpected.one.dlq");
    metrics.recordReceived("unexpected.two.dlq");

    assertThat(counter("awap.dlq.messages", "topic", "other", "legacy", "false").count())
        .isEqualTo(2.0);
    assertThat(registry.find("awap.dlq.messages").counters()).hasSize(1);
  }

  @Test
  void quarantineAndDuplicateCountersAreTopicTagged() {
    metrics.recordQuarantined(DlqTopics.LEGACY_TOPIC);
    metrics.recordDuplicate(DlqTopics.LEGACY_TOPIC);

    assertThat(counter("awap.dlq.quarantined", "topic", "awap.dlq").count()).isEqualTo(1.0);
    assertThat(counter("awap.dlq.duplicates", "topic", "awap.dlq").count()).isEqualTo(1.0);
  }

  private Counter counter(String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    assertThat(counter)
        .as("meter %s with tags %s", name, java.util.Arrays.toString(tags))
        .isNotNull();
    return counter;
  }
}
