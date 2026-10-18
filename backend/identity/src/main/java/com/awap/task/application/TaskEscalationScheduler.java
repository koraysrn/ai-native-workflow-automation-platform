package com.awap.task.application;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import com.awap.task.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Periodically detects tasks whose escalation deadline has passed and publishes an event. */
@Service
public class TaskEscalationScheduler {

  private static final List<TaskStatus> OPEN_STATUSES =
      List.of(TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

  private final TaskRepository taskRepository;
  private final OutboxEventPublisher outboxEventPublisher;
  private final WorkflowInstanceRepository workflowInstanceRepository;

  public TaskEscalationScheduler(
      TaskRepository taskRepository,
      OutboxEventPublisher outboxEventPublisher,
      WorkflowInstanceRepository workflowInstanceRepository) {
    this.taskRepository = taskRepository;
    this.outboxEventPublisher = outboxEventPublisher;
    this.workflowInstanceRepository = workflowInstanceRepository;
  }

  @Scheduled(fixedDelay = 30_000)
  @Transactional
  public void escalateOverdueTasks() {
    List<Task> due = taskRepository.findDueForEscalation(Instant.now(), OPEN_STATUSES);
    for (Task task : due) {
      if (task.getWorkflowInstanceId() != null && isPaused(task)) {
        // Paused workflows must not progress via timers/schedulers.
        continue;
      }
      task.markEscalationDue();
      outboxEventPublisher.publish(
          task.getTenantId(),
          "TASK",
          task.getId(),
          "TASK_ESCALATION_DUE",
          new TaskEscalationDue(task.getId(), task.getAssigneeId()));
    }
  }

  private boolean isPaused(Task task) {
    return workflowInstanceRepository
        .findByIdAndTenantIdAndDeletedFalse(task.getWorkflowInstanceId(), task.getTenantId())
        .map(instance -> instance.getState() == WorkflowState.PAUSED)
        .orElse(false);
  }

  /** Payload published when a task escalates. */
  public record TaskEscalationDue(java.util.UUID taskId, java.util.UUID assigneeId) {}
}
