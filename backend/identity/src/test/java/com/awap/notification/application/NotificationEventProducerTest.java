package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.application.WorkflowLifecycleEvent;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskLifecycleEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Event → notification-request producer: template, channel, locale, recipient and dedupe. */
class NotificationEventProducerTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final UUID tenantId = UUID.randomUUID();
  private final UUID assigneeId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();

  private final User assignee = userWithEmail("assignee@example.com");
  private final User owner = userWithEmail("owner@example.com");
  private final User admin = userWithEmail("admin@example.com");

  private static User userWithEmail(String email) {
    User user = mock(User.class);
    when(user.getEmail()).thenReturn(email);
    return user;
  }

  private NotificationEventProducer producer() {
    return new NotificationEventProducer(
        userRepository,
        outboxEventPublisher,
        new NotificationEventMatrix(),
        registry,
        new ObjectMapper(),
        "EMAIL",
        "en");
  }

  @BeforeEach
  void defaultOwner() {
    when(userRepository.findByIdAndTenantId(ownerId, tenantId)).thenReturn(Optional.of(owner));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturePayload(UUID aggregateId) {
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outboxEventPublisher)
        .publish(
            eq(tenantId),
            eq("NOTIFICATION"),
            eq(aggregateId),
            eq("NOTIFICATION_REQUESTED"),
            payload.capture());
    return (Map<String, Object>) payload.getValue();
  }

  @Test
  void taskAssignmentResolvesTheAssigneeAndPublishesTheMappedTemplate() {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(assigneeId, tenantId))
        .thenReturn(Optional.of(assignee));

    producer()
        .onTaskLifecycle(
            new TaskLifecycleEvent(
                tenantId,
                taskId,
                "TASK_ASSIGNED",
                "Approve invoice",
                assigneeId,
                ownerId,
                null,
                null));

    Map<String, Object> payload = capturePayload(taskId);
    assertThat(payload)
        .containsEntry("recipient", "assignee@example.com")
        .containsEntry("templateKey", "task-assigned")
        .containsEntry("channel", "EMAIL")
        .containsEntry("locale", "en")
        .containsEntry("eventType", "TASK_ASSIGNED")
        .containsEntry("businessKey", "TASK_ASSIGNED:" + taskId);
    assertThat((String) payload.get("variablesJson"))
        .contains("\"title\":\"Approve invoice\"")
        .contains(taskId.toString());
  }

  @Test
  void fallsBackToTheTaskOwnerWhenTheAssigneeIsMissing() {
    UUID taskId = UUID.randomUUID();

    producer()
        .onTaskLifecycle(
            new TaskLifecycleEvent(
                tenantId, taskId, "TASK_APPROVED", "Approve invoice", null, ownerId, null, null));

    assertThat(capturePayload(taskId)).containsEntry("recipient", "owner@example.com");
  }

  @Test
  void fallsBackToTheTenantAdminWhenAssigneeAndOwnerAreUnknown() {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(ownerId, tenantId)).thenReturn(Optional.empty());
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.of(admin));

    producer()
        .onTaskLifecycle(
            new TaskLifecycleEvent(
                tenantId, taskId, "TASK_REJECTED", "Approve invoice", null, ownerId, null, null));

    assertThat(capturePayload(taskId)).containsEntry("recipient", "admin@example.com");
  }

  @Test
  void reportsAMissingRecipientWithoutThrowingOrPublishing() {
    UUID taskId = UUID.randomUUID();
    User blank = userWithEmail("   ");
    when(userRepository.findByIdAndTenantId(ownerId, tenantId)).thenReturn(Optional.empty());
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.of(blank));

    assertThatCode(
            () ->
                producer()
                    .onTaskLifecycle(
                        new TaskLifecycleEvent(
                            tenantId,
                            taskId,
                            "TASK_COMPLETED",
                            "Approve invoice",
                            null,
                            ownerId,
                            null,
                            null)))
        .doesNotThrowAnyException();

    verify(outboxEventPublisher, never()).publish(any(), anyString(), any(), anyString(), any());
    assertThat(
            registry
                .counter(
                    NotificationEventProducer.MISSING_RECIPIENT_METRIC,
                    "eventType",
                    "TASK_COMPLETED")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void usesTheGenericTemplateAndCountsTheFallbackForAnUnmappedEvent() {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(assigneeId, tenantId))
        .thenReturn(Optional.of(assignee));

    producer()
        .onTaskLifecycle(
            new TaskLifecycleEvent(
                tenantId,
                taskId,
                "TASK_REMINDER",
                "Approve invoice",
                assigneeId,
                ownerId,
                null,
                null));

    assertThat(capturePayload(taskId)).containsEntry("templateKey", "notification");
    assertThat(
            registry
                .counter(
                    NotificationEventProducer.TEMPLATE_FALLBACK_METRIC,
                    "eventType",
                    "TASK_REMINDER")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void workflowEventsTargetTheTenantAdminWithWorkflowVariables() {
    UUID instanceId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.of(admin));

    producer()
        .onWorkflowLifecycle(
            new WorkflowLifecycleEvent(
                tenantId, instanceId, definitionId, "WORKFLOW_COMPLETED", "COMPLETED"));

    Map<String, Object> payload = capturePayload(instanceId);
    assertThat(payload)
        .containsEntry("recipient", "admin@example.com")
        .containsEntry("templateKey", "workflow-completed")
        .containsEntry("businessKey", "WORKFLOW_COMPLETED:" + instanceId);
    assertThat((String) payload.get("variablesJson"))
        .contains("\"state\":\"COMPLETED\"")
        .contains(definitionId.toString());
  }

  @Test
  void honoursTheConfiguredChannelAndLocale() {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(assigneeId, tenantId))
        .thenReturn(Optional.of(assignee));
    NotificationEventProducer smsProducer =
        new NotificationEventProducer(
            userRepository,
            outboxEventPublisher,
            new NotificationEventMatrix(),
            registry,
            new ObjectMapper(),
            "sms",
            "tr");

    smsProducer.onTaskLifecycle(
        new TaskLifecycleEvent(
            tenantId,
            taskId,
            "TASK_ESCALATED",
            "Approve invoice",
            assigneeId,
            ownerId,
            null,
            null));

    assertThat(capturePayload(taskId))
        .containsEntry("channel", "SMS")
        .containsEntry("locale", "tr");
  }

  @Test
  void unknownConfiguredChannelFallsBackToEmail() {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(assigneeId, tenantId))
        .thenReturn(Optional.of(assignee));
    NotificationEventProducer other =
        new NotificationEventProducer(
            userRepository,
            outboxEventPublisher,
            new NotificationEventMatrix(),
            registry,
            new ObjectMapper(),
            "carrier-pigeon",
            "en");

    other.onTaskLifecycle(
        new TaskLifecycleEvent(
            tenantId, taskId, "TASK_ASSIGNED", "Approve invoice", assigneeId, ownerId, null, null));

    assertThat(capturePayload(taskId)).containsEntry("channel", "EMAIL");
  }

  @Test
  void serializationFailureDegradesToAnEmptyPayloadAndStillPublishes() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(userRepository.findByIdAndTenantId(assigneeId, tenantId))
        .thenReturn(Optional.of(assignee));
    ObjectMapper failing = mock(ObjectMapper.class);
    when(failing.writeValueAsString(any())).thenThrow(mock(JsonProcessingException.class));
    NotificationEventProducer failingProducer =
        new NotificationEventProducer(
            userRepository,
            outboxEventPublisher,
            new NotificationEventMatrix(),
            registry,
            failing,
            "EMAIL",
            "en");

    failingProducer.onTaskLifecycle(
        new TaskLifecycleEvent(
            tenantId, taskId, "TASK_APPROVED", "Approve invoice", assigneeId, ownerId, null, null));

    assertThat(capturePayload(taskId)).containsEntry("variablesJson", "{}");
  }
}
