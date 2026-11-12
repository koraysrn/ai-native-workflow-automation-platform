package com.awap.notification.application;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.application.WorkflowLifecycleEvent;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.notification.domain.NotificationChannel;
import com.awap.task.application.TaskLifecycleEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns task and workflow lifecycle facts into durable {@code NOTIFICATION_REQUESTED} outbox
 * records (issue #203).
 *
 * <p>The lifecycle events are published in-process inside the caller's transaction, so the outbox
 * row commits atomically with the task/workflow change. The producer resolves:
 *
 * <ul>
 *   <li><b>template</b> from {@link NotificationEventMatrix}, falling back to the generic template
 *       and counting the fallback;
 *   <li><b>channel</b> and <b>locale</b> from tenant configuration ({@code
 *       awap.notification.default-channel} / {@code awap.notification.default-locale});
 *   <li><b>recipient</b> as the assignee, then the task creator (owner), then the tenant admin.
 * </ul>
 *
 * <p>A missing recipient is reported and skipped: a notification must never fail the business
 * transition that produced it. The dedupe/business key is {@code eventType:aggregateId}, so a
 * re-delivered lifecycle event maps to the same delivery row and is suppressed by {@link
 * NotificationService}.
 */
@Component
public class NotificationEventProducer {

  static final String MISSING_RECIPIENT_METRIC = "awap.notification.producer.missing-recipient";
  static final String TEMPLATE_FALLBACK_METRIC = "awap.notification.producer.template-fallback";

  private static final Logger log = LoggerFactory.getLogger(NotificationEventProducer.class);

  private final UserRepository userRepository;
  private final OutboxEventPublisher outboxEventPublisher;
  private final NotificationEventMatrix matrix;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final NotificationChannel channel;
  private final String locale;

  public NotificationEventProducer(
      UserRepository userRepository,
      OutboxEventPublisher outboxEventPublisher,
      NotificationEventMatrix matrix,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      @Value("${awap.notification.default-channel:EMAIL}") String channel,
      @Value("${awap.notification.default-locale:en}") String locale) {
    this.userRepository = userRepository;
    this.outboxEventPublisher = outboxEventPublisher;
    this.matrix = matrix;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
    this.channel = parseChannel(channel);
    this.locale = locale == null || locale.isBlank() ? "en" : locale.trim();
  }

  @EventListener
  public void onTaskLifecycle(TaskLifecycleEvent event) {
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("taskId", String.valueOf(event.taskId()));
    variables.put("title", event.title() == null ? "" : event.title());
    variables.put("eventType", event.eventType());
    variables.put(
        "workflowInstanceId",
        event.workflowInstanceId() == null ? "" : event.workflowInstanceId().toString());
    produce(
        event.tenantId(),
        event.taskId(),
        event.eventType(),
        variables,
        event.assigneeId(),
        event.ownerId());
  }

  @EventListener
  public void onWorkflowLifecycle(WorkflowLifecycleEvent event) {
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("workflowInstanceId", String.valueOf(event.instanceId()));
    variables.put(
        "workflowDefinitionId",
        event.workflowDefinitionId() == null ? "" : event.workflowDefinitionId().toString());
    // The built-in workflow-completed template refers to {{name}}; the definition id is the closest
    // stable human-facing identifier available on the event.
    variables.put(
        "name",
        event.workflowDefinitionId() == null ? "" : event.workflowDefinitionId().toString());
    variables.put("state", event.state() == null ? "" : event.state());
    variables.put("eventType", event.eventType());
    produce(event.tenantId(), event.instanceId(), event.eventType(), variables, null, null);
  }

  private void produce(
      UUID tenantId,
      UUID aggregateId,
      String eventType,
      Map<String, String> variables,
      UUID primaryRecipient,
      UUID secondaryRecipient) {
    String recipient = resolveRecipient(tenantId, primaryRecipient, secondaryRecipient);
    if (recipient == null) {
      meterRegistry.counter(MISSING_RECIPIENT_METRIC, "eventType", safeType(eventType)).increment();
      log.warn(
          "No notification recipient for tenant {} event {} on {}; skipping.",
          tenantId,
          eventType,
          aggregateId);
      return;
    }
    if (!matrix.isMapped(eventType)) {
      meterRegistry.counter(TEMPLATE_FALLBACK_METRIC, "eventType", safeType(eventType)).increment();
      log.warn(
          "No notification template mapped for event {}; using the generic fallback.", eventType);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId.toString());
    payload.put("recipient", recipient);
    payload.put("channel", channel.name());
    payload.put("templateKey", matrix.templateKeyFor(eventType));
    payload.put("locale", locale);
    payload.put("variablesJson", toJson(variables));
    // Stable business identity: one delivery per event type per aggregate. Combined with the
    // event type inside the notification dedupe key, repeated records collapse onto one row.
    payload.put("businessKey", safeType(eventType) + ":" + aggregateId);
    payload.put("eventType", eventType);
    outboxEventPublisher.publish(
        tenantId, "NOTIFICATION", aggregateId, "NOTIFICATION_REQUESTED", payload);
  }

  private String resolveRecipient(UUID tenantId, UUID primary, UUID secondary) {
    String primaryEmail = emailOf(tenantId, primary);
    if (primaryEmail != null) {
      return primaryEmail;
    }
    String secondaryEmail = emailOf(tenantId, secondary);
    if (secondaryEmail != null) {
      return secondaryEmail;
    }
    return userRepository
        .findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN)
        .map(User::getEmail)
        .filter(email -> email != null && !email.isBlank())
        .orElse(null);
  }

  private String emailOf(UUID tenantId, UUID userId) {
    if (userId == null) {
      return null;
    }
    return userRepository
        .findByIdAndTenantId(userId, tenantId)
        .map(User::getEmail)
        .filter(email -> email != null && !email.isBlank())
        .orElse(null);
  }

  private String toJson(Map<String, String> variables) {
    try {
      return objectMapper.writeValueAsString(variables);
    } catch (JsonProcessingException ex) {
      // Variables are a flat string map; serialization cannot fail in practice, but a producer must
      // not fail the business transaction either way.
      log.warn("Failed to serialize notification variables for event; using an empty payload.", ex);
      return "{}";
    }
  }

  private static NotificationChannel parseChannel(String configured) {
    try {
      return NotificationChannel.valueOf(
          configured == null ? "EMAIL" : configured.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      log.warn("Unknown notification channel '{}'; falling back to EMAIL.", configured);
      return NotificationChannel.EMAIL;
    }
  }

  private static String safeType(String eventType) {
    return eventType == null ? "UNKNOWN" : eventType.trim();
  }
}
