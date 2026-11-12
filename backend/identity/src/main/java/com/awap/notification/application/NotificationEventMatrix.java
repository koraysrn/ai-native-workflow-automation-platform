package com.awap.notification.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Documented event-to-template matrix (issue #203). A task or workflow lifecycle event always maps
 * to one template key; an unmapped event type falls back to the generic {@code notification}
 * template instead of failing the producer.
 *
 * <p>The fallback is visible: {@link #isMapped(String)} returns false and the producer increments
 * {@code awap.notification.producer.template-fallback}, so a new event type that was never mapped
 * is noisy rather than silent.
 */
@Component
public final class NotificationEventMatrix {

  /** Template used when an event type has no explicit mapping. */
  public static final String FALLBACK_TEMPLATE = "notification";

  private static final Map<String, String> TEMPLATE_BY_EVENT =
      Map.ofEntries(
          Map.entry("TASK_ASSIGNED", "task-assigned"),
          Map.entry("TASK_APPROVED", "task-approved"),
          Map.entry("TASK_REJECTED", "task-rejected"),
          Map.entry("TASK_COMPLETED", "task-completed"),
          Map.entry("TASK_ESCALATED", "task-escalated"),
          Map.entry("WORKFLOW_STARTED", "workflow-started"),
          Map.entry("WORKFLOW_COMPLETED", "workflow-completed"),
          Map.entry("WORKFLOW_FAILED", "workflow-failed"),
          Map.entry("WORKFLOW_PAUSED", "workflow-paused"),
          Map.entry("WORKFLOW_RESUMED", "workflow-resumed"));

  /** The template key for one event type; the generic fallback for an unknown type. */
  public String templateKeyFor(String eventType) {
    return TEMPLATE_BY_EVENT.getOrDefault(normalize(eventType), FALLBACK_TEMPLATE);
  }

  /** True when the event type has an explicit template mapping. */
  public boolean isMapped(String eventType) {
    return TEMPLATE_BY_EVENT.containsKey(normalize(eventType));
  }

  /** Every mapped event type, in the documented order. */
  public Set<String> mappedEventTypes() {
    return Set.copyOf(TEMPLATE_BY_EVENT.keySet());
  }

  private static String normalize(String eventType) {
    return eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
  }
}
