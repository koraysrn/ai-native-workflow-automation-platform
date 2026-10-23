package com.awap.task.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies overdue task escalation: publishing and paused-workflow skipping. */
@ExtendWith(MockitoExtension.class)
class TaskEscalationSchedulerTest {

  @Mock private TaskRepository taskRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private WorkflowInstanceRepository workflowInstanceRepository;

  @Test
  void publishesEventForDueTask() {
    TaskEscalationScheduler scheduler =
        new TaskEscalationScheduler(
            taskRepository, outboxEventPublisher, workflowInstanceRepository);
    Task task = new Task(UUID.randomUUID(), null, null, "title", "desc", UUID.randomUUID());
    when(taskRepository.findDueForEscalation(any(Instant.class), any())).thenReturn(List.of(task));

    scheduler.escalateOverdueTasks();

    verify(outboxEventPublisher)
        .publish(
            eq(task.getTenantId()), eq("TASK"), eq(task.getId()), eq("TASK_ESCALATION_DUE"), any());
  }

  @Test
  void skipsPausedWorkflowTasks() {
    TaskEscalationScheduler scheduler =
        new TaskEscalationScheduler(
            taskRepository, outboxEventPublisher, workflowInstanceRepository);
    UUID instanceId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    Task task = new Task(tenantId, instanceId, null, "title", "desc", UUID.randomUUID());
    WorkflowInstance paused =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.PAUSED, null, null);
    when(taskRepository.findDueForEscalation(any(Instant.class), any())).thenReturn(List.of(task));
    when(workflowInstanceRepository.findByIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
        .thenReturn(Optional.of(paused));

    scheduler.escalateOverdueTasks();

    verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
  }

  @Test
  void doesNothingWhenNoTasksAreDue() {
    TaskEscalationScheduler scheduler =
        new TaskEscalationScheduler(
            taskRepository, outboxEventPublisher, workflowInstanceRepository);
    when(taskRepository.findDueForEscalation(any(Instant.class), any())).thenReturn(List.of());

    scheduler.escalateOverdueTasks();

    verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
  }
}
