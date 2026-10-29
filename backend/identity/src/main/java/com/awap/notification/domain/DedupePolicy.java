package com.awap.notification.domain;

import java.util.Locale;
import java.util.Set;

/**
 * De-duplication policy for a notification event type.
 *
 * <p>The policy decides how the business identity of a notification is derived:
 *
 * <ul>
 *   <li>{@link #TRANSACTIONAL} – the event represents a single business fact (a workflow instance
 *       transition, a task decision, an approval or a form submission). The business key is
 *       <b>required</b> so that two genuinely different business facts are never merged. When the
 *       producer cannot supply one, the key falls back to a hash of the variables payload, which
 *       preserves the old content-based idempotency for legacy producers.
 *   <li>{@link #PERIODIC} – the event is a reminder/digest/escalation. Repeats are legitimate and
 *       are suppressed only for the configured time window; the business key is optional. The
 *       variables payload is intentionally excluded from the key so a reminder with changing copy
 *       still maps to the same periodic slot.
 * </ul>
 *
 * <p>Documented per-event-type mapping:
 *
 * <table border="1">
 *   <caption>Event type to policy mapping</caption>
 *   <tr><th>Policy</th><th>Event types</th></tr>
 *   <tr>
 *     <td>{@link #TRANSACTIONAL}</td>
 *     <td>{@code WORKFLOW_INSTANCE_*}, {@code WORKFLOW_COMPLETED}, {@code WORKFLOW_FAILED},
 *     {@code WORKFLOW_CANCELLED}, {@code TASK_ASSIGNED}, {@code TASK_COMPLETED},
 *     {@code TASK_DECISION}, {@code APPROVAL_*}, {@code FORM_*}, {@code NOTIFICATION_REQUESTED}
 *     and any unknown event type (the safe default).</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PERIODIC}</td>
 *     <td>{@code TASK_ESCALATED}, {@code TASK_REMINDER}, {@code SLA_BREACH}, {@code SLA_WARNING},
 *     {@code DAILY_DIGEST}, {@code WEEKLY_DIGEST}.</td>
 *   </tr>
 * </table>
 *
 * <p>The mapping is deliberately fail-safe: an unrecognised event type is treated as transactional,
 * because wrongly merging two distinct business facts is worse than an extra notification.
 */
public enum DedupePolicy {
  TRANSACTIONAL,
  PERIODIC;

  /** Event types whose repeats are legitimate and therefore only time-boxed. */
  private static final Set<String> PERIODIC_EVENT_TYPES =
      Set.of(
          "TASK_ESCALATED",
          "TASK_REMINDER",
          "SLA_BREACH",
          "SLA_WARNING",
          "DAILY_DIGEST",
          "WEEKLY_DIGEST");

  /** Resolves the documented policy for an incoming event type; unknown values fail safe. */
  public static DedupePolicy forEventType(String eventType) {
    if (eventType == null || eventType.isBlank()) {
      return TRANSACTIONAL;
    }
    String normalized = eventType.trim().toUpperCase(Locale.ROOT);
    return PERIODIC_EVENT_TYPES.contains(normalized) ? PERIODIC : TRANSACTIONAL;
  }

  /** True when the key builder must prefer an explicit business key over the payload hash. */
  public boolean requiresBusinessKey() {
    return this == TRANSACTIONAL;
  }
}
