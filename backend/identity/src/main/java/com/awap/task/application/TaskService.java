package com.awap.task.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.NodeCompletedEvent;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskAssignRequest;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.task.interfaces.dto.TaskResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task lifecycle use cases: create, assign, reassign, claim, decide, complete and escalate. */
@Service
public class TaskService {

  private final TaskRepository taskRepository;
  private final OutboxEventPublisher outboxEventPublisher;

  public TaskService(TaskRepository taskRepository, OutboxEventPublisher outboxEventPublisher) {
    this.taskRepository = taskRepository;
    this.outboxEventPublisher = outboxEventPublisher;
  }

  @Transactional
  public TaskResponse create(UUID tenantId, TaskRequest request) {
    Task task =
        new Task(
            tenantId,
            request.workflowInstanceId(),
            request.nodeExecutionId(),
            request.title(),
            request.description(),
            request.assigneeId());
    taskRepository.save(task);
    return toResponse(task);
  }

  public List<TaskResponse> list(UUID tenantId) {
    return taskRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(this::toResponse)
        .toList();
  }

  public List<TaskResponse> listMine(UUID tenantId, UUID userId) {
    return taskRepository.findByTenantIdAndAssigneeIdAndDeletedFalse(tenantId, userId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public TaskResponse assign(UUID tenantId, UUID id, TaskAssignRequest request) {
    Task task = find(tenantId, id);
    runGuarded(() -> task.assign(request.assigneeId()));
    return toResponse(task);
  }

  @Transactional
  public TaskResponse reassign(UUID tenantId, UUID id, TaskAssignRequest request) {
    Task task = find(tenantId, id);
    runGuarded(() -> task.reassign(request.assigneeId()));
    return toResponse(task);
  }

  @Transactional
  public TaskResponse claim(UUID tenantId, UUID id, UUID userId) {
    Task task = find(tenantId, id);
    runGuarded(() -> task.claim(userId));
    return toResponse(task);
  }

  @Transactional
  public TaskResponse approve(
      UUID tenantId, UUID id, UUID actorId, String actorRole, String comment) {
    Task task = find(tenantId, id);
    requireDecisionRight(task, actorId, actorRole);
    runGuarded(() -> task.approve(comment));
    publishCompleted(task, "{\"decision\":\"APPROVED\"}");
    return toResponse(task);
  }

  @Transactional
  public TaskResponse reject(
      UUID tenantId, UUID id, UUID actorId, String actorRole, String comment) {
    Task task = find(tenantId, id);
    requireDecisionRight(task, actorId, actorRole);
    runGuarded(() -> task.reject(comment));
    publishCompleted(task, "{\"decision\":\"REJECTED\"}");
    return toResponse(task);
  }

  @Transactional
  public TaskResponse complete(UUID tenantId, UUID id) {
    Task task = find(tenantId, id);
    runGuarded(task::complete);
    publishCompleted(task, "{\"decision\":\"COMPLETED\"}");
    return toResponse(task);
  }

  @Transactional
  public TaskResponse escalate(UUID tenantId, UUID id, TaskAssignRequest request) {
    Task task = find(tenantId, id);
    runGuarded(() -> task.escalate(request.assigneeId(), Instant.now().plusSeconds(3600)));
    return toResponse(task);
  }

  private void publishCompleted(Task task, String outputJson) {
    if (task.getWorkflowInstanceId() != null && task.getNodeExecutionId() != null) {
      outboxEventPublisher.publish(
          task.getTenantId(),
          "TASK",
          task.getId(),
          "TASK_COMPLETED",
          new NodeCompletedEvent(
              task.getTenantId(),
              task.getWorkflowInstanceId(),
              task.getNodeExecutionId(),
              outputJson));
    }
  }

  private void requireDecisionRight(Task task, UUID actorId, String actorRole) {
    if (task.getAssigneeId() != null
        && !task.getAssigneeId().equals(actorId)
        && !isManager(actorRole)) {
      throw new AwapException(
          ErrorCode.TASK_ACCESS_DENIED, HttpStatus.FORBIDDEN, "Task is assigned to another user.");
    }
  }

  private boolean isManager(String role) {
    return "MANAGER".equals(role) || "TENANT_ADMIN".equals(role) || "SYSTEM_ADMIN".equals(role);
  }

  private Task find(UUID tenantId, UUID id) {
    return taskRepository
        .findByIdAndTenantIdAndDeletedFalse(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Task not found."));
  }

  private void runGuarded(Runnable action) {
    try {
      action.run();
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
  }

  private TaskResponse toResponse(Task task) {
    return new TaskResponse(
        task.getId(),
        task.getTenantId(),
        task.getWorkflowInstanceId(),
        task.getTitle(),
        task.getDescription(),
        task.getAssigneeId(),
        task.getStatus().name(),
        task.getVersion(),
        task.getDecisionComment());
  }
}
