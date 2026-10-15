package com.awap.task.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskAssignRequest;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.task.interfaces.dto.TaskResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task lifecycle use cases: create, assign, claim, approve, reject and complete. */
@Service
public class TaskService {

  private final TaskRepository taskRepository;

  public TaskService(TaskRepository taskRepository) {
    this.taskRepository = taskRepository;
  }

  @Transactional
  public TaskResponse create(UUID tenantId, TaskRequest request) {
    Task task =
        new Task(
            tenantId,
            request.workflowInstanceId(),
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
  public TaskResponse claim(UUID tenantId, UUID id, UUID userId) {
    Task task = find(tenantId, id);
    runGuarded(() -> task.claim(userId));
    return toResponse(task);
  }

  @Transactional
  public TaskResponse approve(UUID tenantId, UUID id) {
    Task task = find(tenantId, id);
    runGuarded(task::approve);
    return toResponse(task);
  }

  @Transactional
  public TaskResponse reject(UUID tenantId, UUID id) {
    Task task = find(tenantId, id);
    runGuarded(task::reject);
    return toResponse(task);
  }

  @Transactional
  public TaskResponse complete(UUID tenantId, UUID id) {
    Task task = find(tenantId, id);
    runGuarded(task::complete);
    return toResponse(task);
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
        task.getVersion());
  }
}
