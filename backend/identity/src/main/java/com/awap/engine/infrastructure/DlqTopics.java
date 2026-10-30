package com.awap.engine.infrastructure;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Topic naming rules shared by the dead-letter listener and the legacy copy tool (issue #123).
 *
 * <p>The per-topic listener consumes every {@code <source>.dlq} topic. The legacy {@code awap.dlq}
 * topic is handled by the same listener only while the deprecation flag is enabled; the pattern is
 * always anchored so an unrelated topic such as {@code not-a-dlq-topic} can never be routed to the
 * DLQ handler.
 */
public final class DlqTopics {

  /** Topic used by older deployments; dual-consumed for one release, then removed. */
  public static final String LEGACY_TOPIC = "awap.dlq";

  /** Safe target for legacy messages whose original topic is unknown or unsafe. */
  public static final String LEGACY_FALLBACK_TARGET = "awap.legacy.dlq";

  /** Bounded tag value used when an unexpected {@code *.dlq} topic appears. */
  public static final String OTHER_TAG = "other";

  /**
   * Anchored per-topic pattern. The negative lookahead keeps the legacy topic on its own flag so
   * disabling the flag really stops consuming it instead of silently matching it again.
   */
  public static final String PER_TOPIC_PATTERN = "^(?!awap\\.dlq$)[A-Za-z0-9._-]+\\.dlq$";

  /** Source topics whose DLQ names are allowed as metric tag values. */
  public static final Set<String> DEFAULT_KNOWN_TOPICS =
      Set.of(
          "workflow.task.events",
          "workflow.instance.events",
          "workflow.subprocess.events",
          "workflow.saga.events",
          "analytics.events",
          "document.events",
          "notification.send",
          "notification.requested.events");

  private static final Pattern SAFE_TOPIC_NAME = Pattern.compile("[A-Za-z0-9._-]{1,249}");

  private DlqTopics() {}

  /** Returns the anchored pattern covering the per-topic DLQs and optionally the legacy topic. */
  public static String subscriptionPattern(boolean legacyEnabled, String legacyTopic) {
    if (!legacyEnabled) {
      return PER_TOPIC_PATTERN;
    }
    return "(?:" + PER_TOPIC_PATTERN + "|^" + Pattern.quote(legacyTopic) + "$)";
  }

  public static boolean matches(String topic, boolean legacyEnabled, String legacyTopic) {
    return topic != null
        && Pattern.compile(subscriptionPattern(legacyEnabled, legacyTopic))
            .matcher(topic)
            .matches();
  }

  public static boolean isLegacy(String topic) {
    return LEGACY_TOPIC.equals(topic);
  }

  /**
   * Maps a topic to a bounded metric tag value so a malicious or misconfigured producer cannot
   * create unbounded Prometheus series.
   */
  public static String metricTag(String topic, Set<String> knownSourceTopics) {
    if (isLegacy(topic)) {
      return LEGACY_TOPIC;
    }
    if (topic != null && knownSourceTopics.contains(stripDlqSuffix(topic))) {
      return topic;
    }
    return OTHER_TAG;
  }

  /**
   * Resolves the destination for a copied legacy message. Only a safe, known-shaped original topic
   * is honoured; anything else is routed to the fallback topic so no message is dropped.
   */
  public static String copyTarget(String originalTopic, String fallbackTopic, String sourceTopic) {
    if (originalTopic == null
        || originalTopic.isBlank()
        || !SAFE_TOPIC_NAME.matcher(originalTopic).matches()
        || isLegacy(originalTopic)) {
      return fallbackTopic;
    }
    String target = originalTopic.endsWith(".dlq") ? originalTopic : originalTopic + ".dlq";
    if (target.equals(sourceTopic)) {
      return fallbackTopic;
    }
    return target;
  }

  private static String stripDlqSuffix(String topic) {
    return topic.endsWith(".dlq") ? topic.substring(0, topic.length() - ".dlq".length()) : topic;
  }
}
