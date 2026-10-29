package com.awap.engine.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Computes the listener subscription pattern once at startup.
 *
 * <p>The legacy {@code awap.dlq} topic is dual-consumed by default for one release. Removing it
 * from the subscription is a configuration change ({@code awap.kafka.legacy-dlq-enabled=false})
 * and, after the deprecation window, a code change that deletes this class and the property (see
 * runbook O7).
 */
@Component
public class DlqTopicSubscription {

  private final boolean legacyEnabled;
  private final String legacyTopic;
  private final String perTopicPattern;
  private final String topicPattern;

  public DlqTopicSubscription(
      @Value("${awap.kafka.legacy-dlq-enabled:true}") boolean legacyEnabled,
      @Value("${awap.kafka.legacy-dlq-topic:awap.dlq}") String legacyTopic,
      @Value("${awap.kafka.dlq-topic-pattern:" + DlqTopics.PER_TOPIC_PATTERN + "}")
          String perTopicPattern) {
    if (perTopicPattern == null || perTopicPattern.isBlank()) {
      throw new IllegalArgumentException("awap.kafka.dlq-topic-pattern must not be blank.");
    }
    this.legacyEnabled = legacyEnabled;
    this.legacyTopic =
        legacyTopic == null || legacyTopic.isBlank() ? DlqTopics.LEGACY_TOPIC : legacyTopic;
    this.perTopicPattern = perTopicPattern;
    this.topicPattern = DlqTopics.subscriptionPattern(legacyEnabled, this.legacyTopic);
  }

  public String topicPattern() {
    return topicPattern;
  }

  public boolean legacyEnabled() {
    return legacyEnabled;
  }

  public String legacyTopic() {
    return legacyTopic;
  }

  public String perTopicPattern() {
    return perTopicPattern;
  }

  public boolean matches(String topic) {
    return topic != null && java.util.regex.Pattern.matches(topicPattern, topic);
  }
}
