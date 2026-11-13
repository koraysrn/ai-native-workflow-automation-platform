package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/** Dedicated escalation consumer behaviour (issue #207). */
class TaskEscalationServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

  private final TaskRepository taskRepository = mock(TaskRepository.class);
  private final WorkflowInstanceRepository workflowInstanceRepository =
      mock(WorkflowInstanceRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final UUID tenantId = UUID.randomUUID();
  private final UUID backupId = UUID.randomUUID();

  private TaskEscalationService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskEscalationService(
            taskRepository,
            workflowInstanceRepository,
            userRepository,
            outbox,
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Task task() {
    Task task = new Task(tenantId, null, null, "Approve invoice", "desc", UUID.randomUUID());
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(task.getId(), tenantId))
        .thenReturn(Optional.of(task));
    return task;
  }

  private User user(Role role) {
    return new User(tenantId, role.name().toLowerCase() + "@example.com", "hash", "User", role);
  }

  private double skipped(String reason) {
    return registry
        .get(TaskEscalationService.SKIPPED_METRIC)
        .tag("reason", reason)
        .counter()
        .count();
  }

  @Test
  void reassignsToTheConfiguredBackupAndPublishesTheNotificationFact() {
    Task task = task();
    when(userRepository.findByIdAndTenantId(backupId, tenantId))
        .thenReturn(Optional.of(user(Role.MANAGER)));

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), backupId));

    assertThat(task.getAssigneeId()).isEqualTo(backupId);
    assertThat(task.isEscalationApplied()).isTrue();
    assertThat(task.getEscalationAppliedAt()).isEqualTo(NOW);
    assertThat(registry.get(TaskEscalationService.HANDLED_METRIC).counter().count()).isEqualTo(1.0);

    ArgumentCaptor<TaskLifecycleEvent> captor = ArgumentCaptor.forClass(TaskLifecycleEvent.class);
    verify(outbox)
        .publish(
            eq(tenantId), eq("TASK"), eq(task.getId()), eq("TASK_ESCALATED"), captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo("TASK_ESCALATED");
    assertThat(captor.getValue().assigneeId()).isEqualTo(backupId);
    assertThat(captor.getValue().title()).isEqualTo("Approve invoice");
  }

  @Test
  void aDuplicateEventDoesNotReassignOrNotifyAgain() {
    Task task = task();
    task.markEscalationApplied(NOW.minusSeconds(30));

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), backupId));

    assertThat(task.getAssigneeId()).isNotEqualTo(backupId);
    assertThat(skipped("duplicate")).isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
  }

  static Stream<String> terminalStatuses() {
    return Stream.of("REJECTED", "APPROVED", "COMPLETED", "CANCELLED");
  }

  @ParameterizedTest
  @MethodSource("terminalStatuses")
  void terminalTasksAreIgnored(String status) {
    Task task = task();
    switch (status) {
      case "REJECTED" -> task.reject();
      case "APPROVED" -> task.approve();
      case "COMPLETED" -> task.complete();
      case "CANCELLED" -> task.cancel();
      default -> throw new IllegalArgumentException(status);
    }
    UUID originalAssignee = task.getAssigneeId();

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), backupId));

    assertThat(task.getAssigneeId()).isEqualTo(originalAssignee);
    assertThat(task.isEscalationApplied()).isFalse();
    assertThat(skipped("terminal")).isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
  }

  @Test
  void aPausedWorkflowIsNotEscalated() {
    Task task = task();
    WorkflowInstance paused =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.PAUSED, null, null);
    Task pausedTask =
        new Task(tenantId, UUID.randomUUID(), null, "Paused", null, UUID.randomUUID());
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(pausedTask.getId(), tenantId))
        .thenReturn(Optional.of(pausedTask));
    when(workflowInstanceRepository.findByIdAndTenantIdAndDeletedFalse(
            pausedTask.getWorkflowInstanceId(), tenantId))
        .thenReturn(Optional.of(paused));

    service.escalate(new TaskEscalationDue(tenantId, pausedTask.getId(), backupId));

    assertThat(pausedTask.isEscalationApplied()).isFalse();
    assertThat(skipped("paused")).isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
    assertThat(task.getAssigneeId()).isNotEqualTo(backupId);
  }

  @Test
  void aTaskWhoseWorkflowInstanceIsMissingIsStillEscalated() {
    Task task =
        new Task(tenantId, UUID.randomUUID(), null, "No instance row", null, UUID.randomUUID());
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(task.getId(), tenantId))
        .thenReturn(Optional.of(task));
    when(workflowInstanceRepository.findByIdAndTenantIdAndDeletedFalse(
            task.getWorkflowInstanceId(), tenantId))
        .thenReturn(Optional.empty());
    when(userRepository.findByIdAndTenantId(backupId, tenantId))
        .thenReturn(Optional.of(user(Role.MANAGER)));

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), backupId));

    assertThat(task.getAssigneeId()).isEqualTo(backupId);
    assertThat(task.isEscalationApplied()).isTrue();
  }

  @Test
  void aMissingBackupFallsBackToTheManagerAndRecordsTheFallback() {
    Task task = task();
    User manager = user(Role.MANAGER);
    when(userRepository.findByIdAndTenantId(backupId, tenantId)).thenReturn(Optional.empty());
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.MANAGER))
        .thenReturn(Optional.of(manager));

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), backupId));

    assertThat(task.getAssigneeId()).isEqualTo(manager.getId());
    assertThat(
            registry
                .get(TaskEscalationService.FALLBACK_METRIC)
                .tag("source", "manager")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void aMissingManagerFallsBackToTheTenantAdmin() {
    Task task = task();
    User admin = user(Role.TENANT_ADMIN);
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.MANAGER))
        .thenReturn(Optional.empty());
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.of(admin));

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), null));

    assertThat(task.getAssigneeId()).isEqualTo(admin.getId());
    assertThat(
            registry
                .get(TaskEscalationService.FALLBACK_METRIC)
                .tag("source", "tenant_admin")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void noManagerOrAdminSkipsSafely() {
    Task task = task();
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.MANAGER))
        .thenReturn(Optional.empty());
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.empty());

    service.escalate(new TaskEscalationDue(tenantId, task.getId(), null));

    assertThat(task.isEscalationApplied()).isFalse();
    assertThat(skipped("missing_target")).isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
  }

  @Test
  void anUnknownTaskIsIgnored() {
    UUID missingTaskId = UUID.randomUUID();
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(missingTaskId, tenantId))
        .thenReturn(Optional.empty());

    service.escalate(new TaskEscalationDue(tenantId, missingTaskId, backupId));

    assertThat(skipped("missing_task")).isEqualTo(1.0);
  }
}
