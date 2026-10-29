package com.awap.engine.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Topic-tagged DLQ metrics with bounded cardinality.
 *
 * <p>Only topics that map to a known source topic (or the legacy topic itself) are used as tag
 * values; every other {@code *.dlq} topic is folded into {@link DlqTopics#OTHER_TAG}. The legacy
 * counter is intentionally untagged so dashboards and alerts do not have to know about the
 * per-topic series.
 */
@Component
public class DlqMetrics {

  public static final String RECEIVED_METRIC = "awap.dlq.messages";
  public static final String LEGACY_METRIC = "awap.dlq.legacy.messages";
  public static final String QUARANTINED_METRIC = "awap.dlq.quarantined";
  public static final String DUPLICATE_METRIC = "awap.dlq.duplicates";

  private final MeterRegistry meterRegistry;
  private final Set<String> knownSourceTopics;

  public DlqMetrics(
      MeterRegistry meterRegistry,
      @Value(
              "${awap.kafka.dlq-known-topics:workflow.task.events,workflow.instance.events,"
                  + "workflow.subprocess.events,analytics.events,document.events,"
                  + "notification.send,notification.requested.events}")
          String knownTopics) {
    this.meterRegistry = meterRegistry;
    this.knownSourceTopics = parseKnownTopics(knownTopics);
  }

  public void recordReceived(String topic) {
    meterRegistry
        .counter(
            RECEIVED_METRIC,
            "topic",
            DlqTopics.metricTag(topic, knownSourceTopics),
            "legacy",
            Boolean.toString(DlqTopics.isLegacy(topic)))
        .increment();
    if (DlqTopics.isLegacy(topic)) {
      meterRegistry.counter(LEGACY_METRIC).increment();
    }
  }

  public void recordQuarantined(String topic) {
    meterRegistry
        .counter(QUARANTINED_METRIC, "topic", DlqTopics.metricTag(topic, knownSourceTopics))
        .increment();
  }

  public void recordDuplicate(String topic) {
    meterRegistry
        .counter(DUPLICATE_METRIC, "topic", DlqTopics.metricTag(topic, knownSourceTopics))
        .increment();
  }

  private static Set<String> parseKnownTopics(String knownTopics) {
    if (knownTopics == null || knownTopics.isBlank()) {
      return Set.of();
    }
    Set<String> topics = new LinkedHashSet<>();
    Arrays.stream(knownTopics.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .forEach(topics::add);
    return Set.copyOf(topics);
  }
}
