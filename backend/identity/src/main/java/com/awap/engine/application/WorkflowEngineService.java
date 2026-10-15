package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.ExecutionLogResponse;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow engine use cases: start instances, advance states and audit every transition. */
@Service
public class WorkflowEngineService {

  private final WorkflowInstanceRepository instanceRepository;
  private final ExecutionLogRepository executionLogRepository;
  private final WorkflowDefinitionRepository definitionRepository;

  public WorkflowEngineService(
      WorkflowInstanceRepository instanceRepository,
      ExecutionLogRepository executionLogRepository,
      WorkflowDefinitionRepository definitionRepository) {
    this.instanceRepository = instanceRepository;
    this.executionLogRepository = executionLogRepository;
    this.definitionRepository = definitionRepository;
  }

  @Transactional
  public WorkflowInstanceResponse start(UUID tenantId, StartWorkflowRequest request) {
    String idempotencyKey = request.idempotencyKey();
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var existing = instanceRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
      if (existing.isPresent()) {
        return toResponse(existing.get());
      }
    }

    WorkflowDefinition definition =
        definitionRepository
            .findByIdAndTenantIdAndDeletedFalse(request.workflowDefinitionId(), tenantId)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Workflow definition not found."));
    if (definition.getStatus() != WorkflowStatus.PUBLISHED) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Workflow definition is not published.");
    }

    WorkflowInstance instance =
        new WorkflowInstance(
            tenantId,
            definition.getId(),
            definition.getCurrentVersion(),
            WorkflowState.RUNNING,
            idempotencyKey,
            request.contextJson());
    instanceRepository.save(instance);
    log(instance, "START", "START", "WORKFLOW_STARTED", "Workflow started.");
    return toResponse(instance);
  }

  public List<WorkflowInstanceResponse> list(UUID tenantId) {
    return instanceRepository.findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(tenantId).stream()
        .map(this::toResponse)
        .toList();
  }

  public WorkflowInstanceResponse get(UUID tenantId, UUID id) {
    return toResponse(find(tenantId, id));
  }

  public List<ExecutionLogResponse> logs(UUID tenantId, UUID id) {
    find(tenantId, id);
    return executionLogRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(id).stream()
        .map(this::toLogResponse)
        .toList();
  }

  @Transactional
  public WorkflowInstanceResponse complete(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
    return toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse fail(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.FAILED, "WORKFLOW_FAILED");
    return toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse cancel(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.CANCELLED, "WORKFLOW_CANCELLED");
    return toResponse(instance);
  }

  private WorkflowInstance find(UUID tenantId, UUID id) {
    return instanceRepository
        .findByIdAndTenantIdAndDeletedFalse(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Workflow instance not found."));
  }

  private void transition(WorkflowInstance instance, WorkflowState target, String action) {
    try {
      instance.transitionTo(target);
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
    log(instance, null, null, action, "State advanced to " + target);
  }

  private void log(
      WorkflowInstance instance,
      String nodeId,
      String nodeType,
      String action,
      String payload) {
    ExecutionLog log =
        new ExecutionLog(instance.getTenantId(), instance.getId(), nodeId, nodeType, action, payload);
    executionLogRepository.save(log);
  }

  private WorkflowInstanceResponse toResponse(WorkflowInstance instance) {
    return new WorkflowInstanceResponse(
        instance.getId(),
        instance.getTenantId(),
        instance.getWorkflowDefinitionId(),
        instance.getWorkflowVersion(),
        instance.getState().name(),
        instance.getContextJson(),
        instance.getIdempotencyKey(),
        instance.getCreatedAt());
  }

  private ExecutionLogResponse toLogResponse(ExecutionLog log) {
    return new ExecutionLogResponse(
        log.getId(),
        log.getWorkflowInstanceId(),
        log.getNodeId(),
        log.getNodeType(),
        log.getAction(),
        log.getPayload(),
        log.getCreatedAt());
  }
}
