package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionContext;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.EngineMapper;
import com.awap.engine.interfaces.dto.ExecutionLogResponse;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.WorkflowDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow engine facade: starts instances, executes nodes deterministically and audits every step.
 * The node-type dispatch, transition resolution and node config parsing are delegated to {@link
 * NodeExecutor}, {@link TransitionResolver} and {@link NodeConfigReader} respectively, while this
 * class remains the transaction boundary and public API.
 */
@Service
public class WorkflowEngineService implements WorkflowEnginePort {

  private final WorkflowInstanceRepository instanceRepository;
  private final ExecutionLogRepository executionLogRepository;
  private final WorkflowDefinitionService workflowDefinitionService;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final ExecutionContextRepository executionContextRepository;
  private final ObjectMapper objectMapper;
  private final OutboxEventPublisher outboxEventPublisher;
  private final EngineMapper engineMapper;

  private final NodeConfigReader configReader;
  private final TransitionResolver transitionResolver;
  private final NodeExecutor nodeExecutor;

  public WorkflowEngineService(
      WorkflowInstanceRepository instanceRepository,
      ExecutionLogRepository executionLogRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeExecutionRepository nodeExecutionRepository,
      ExecutionContextRepository executionContextRepository,
      TaskService taskService,
      SpelExpressionService spelExpressionService,
      ObjectMapper objectMapper,
      UserRepository userRepository,
      OutboxEventPublisher outboxEventPublisher,
      EngineMapper engineMapper) {
    this.instanceRepository = instanceRepository;
    this.executionLogRepository = executionLogRepository;
    this.workflowDefinitionService = workflowDefinitionService;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.executionContextRepository = executionContextRepository;
    this.objectMapper = objectMapper;
    this.outboxEventPublisher = outboxEventPublisher;
    this.engineMapper = engineMapper;

    this.configReader = new NodeConfigReader(objectMapper);
    this.transitionResolver =
        new TransitionResolver(
            nodeExecutionRepository,
            workflowDefinitionService,
            executionContextRepository,
            spelExpressionService,
            objectMapper,
            this);
    this.nodeExecutor =
        new NodeExecutor(
            nodeExecutionRepository,
            workflowDefinitionService,
            configReader,
            taskService,
            userRepository,
            this);
  }

  @Transactional
  public WorkflowInstanceResponse start(UUID tenantId, StartWorkflowRequest request) {
    if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
      var existing =
          instanceRepository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
      if (existing.isPresent()) {
        return engineMapper.toResponse(existing.get());
      }
    }

    WorkflowDefinition definition =
        workflowDefinitionService.getPublished(tenantId, request.workflowDefinitionId());

    WorkflowInstance instance =
        new WorkflowInstance(
            tenantId,
            definition.getId(),
            definition.getCurrentVersion(),
            WorkflowState.CREATED,
            request.idempotencyKey(),
            request.contextJson());
    instanceRepository.save(instance);
    executionContextRepository.save(
        new ExecutionContext(tenantId, instance.getId(), request.contextJson()));

    instance.transitionTo(WorkflowState.STARTED);
    log(instance, null, null, "INSTANCE_STARTED", "Workflow instance started.");

    nodeExecutor.executeStartNode(instance);
    return engineMapper.toResponse(instance);
  }

  public List<WorkflowInstanceResponse> list(UUID tenantId) {
    return instanceRepository.findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(tenantId).stream()
        .map(engineMapper::toResponse)
        .toList();
  }

  public WorkflowInstanceResponse get(UUID tenantId, UUID id) {
    return engineMapper.toResponse(find(tenantId, id));
  }

  public List<ExecutionLogResponse> logs(UUID tenantId, UUID id) {
    find(tenantId, id);
    return executionLogRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(id).stream()
        .map(engineMapper::toLogResponse)
        .toList();
  }

  @Transactional
  public WorkflowInstanceResponse completeNode(
      UUID tenantId, UUID instanceId, String nodeId, String outputJson) {
    WorkflowInstance instance = find(tenantId, instanceId);
    NodeExecution execution = findNodeExecution(instanceId, nodeId);
    if (execution.getStatus() != NodeExecutionStatus.COMPLETED) {
      execution.complete(outputJson);
      nodeExecutionRepository.save(execution);
      mergeOutputIntoContext(instance, nodeId, outputJson);
      log(instance, nodeId, execution.getNodeType(), "NODE_COMPLETED", outputJson);
    }
    transitionResolver.advanceFrom(instance, nodeId);
    return engineMapper.toResponse(instance);
  }

  @EventListener
  public void onNodeCompleted(NodeCompletedEvent event) {
    NodeExecution execution =
        nodeExecutionRepository
            .findById(event.nodeExecutionId())
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Node execution not found."));
    completeNode(
        event.tenantId(), event.workflowInstanceId(), execution.getNodeId(), event.outputJson());
  }

  @Transactional
  public WorkflowInstanceResponse retry(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.RUNNING, "WORKFLOW_RETRIED");
    nodeExecutor.resumeFailedNodes(instance);
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse replay(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    // Deterministic replay: re-drive failed nodes idempotently from the recorded
    // execution state. External side effects are not repeated (no external-call
    // node types exist in Phase 0).
    transition(instance, WorkflowState.RUNNING, "WORKFLOW_REPLAYED");
    nodeExecutor.resumeFailedNodes(instance);
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse complete(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse fail(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.FAILED, "WORKFLOW_FAILED");
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse cancel(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    transition(instance, WorkflowState.CANCELLED, "WORKFLOW_CANCELLED");
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse pause(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    guarded(instance::pause);
    log(instance, null, null, "WORKFLOW_PAUSED", "Workflow instance paused.");
    outboxEventPublisher.publish(
        tenantId, "WORKFLOW", instance.getId(), "WORKFLOW_PAUSED", Map.of("state", "PAUSED"));
    return engineMapper.toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse resume(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    guarded(instance::resume);
    log(instance, null, null, "WORKFLOW_RESUMED", "Workflow instance resumed.");
    outboxEventPublisher.publish(
        tenantId, "WORKFLOW", instance.getId(), "WORKFLOW_RESUMED", Map.of("state", "RUNNING"));
    // Re-drive completed nodes whose outgoing transitions were skipped while the
    // instance was paused, so resume actually continues execution instead of
    // leaving the workflow stuck in RUNNING.
    transitionResolver.resumeCompletedNodes(instance);
    return engineMapper.toResponse(instance);
  }

  /**
   * Resumes a delayed node whose resume instant has elapsed. Idempotent: only WAITING nodes with a
   * scheduled resume time are completed and advanced.
   */
  @Transactional
  public WorkflowInstanceResponse resumeDueNode(UUID tenantId, UUID instanceId, String nodeId) {
    WorkflowInstance instance = find(tenantId, instanceId);
    NodeExecution execution = findNodeExecution(instanceId, nodeId);
    if (execution.getStatus() == NodeExecutionStatus.WAITING && execution.getResumeAt() != null) {
      execution.clearResumeAt();
      execution.complete("{}");
      nodeExecutionRepository.save(execution);
      log(instance, nodeId, execution.getNodeType(), "NODE_COMPLETED", null);
      transitionResolver.advanceFrom(instance, nodeId);
    }
    return engineMapper.toResponse(instance);
  }

  @Override
  public void executeNode(WorkflowInstance instance, NodeDefinition node) {
    nodeExecutor.executeNode(instance, node);
  }

  @Override
  public void advanceFrom(WorkflowInstance instance, String fromNodeId) {
    transitionResolver.advanceFrom(instance, fromNodeId);
  }

  private void mergeOutputIntoContext(WorkflowInstance instance, String nodeId, String outputJson) {
    if (outputJson == null || outputJson.isBlank()) {
      return;
    }
    executionContextRepository
        .findByWorkflowInstanceId(instance.getId())
        .ifPresent(
            context -> {
              Map<String, Object> variables = transitionResolver.readVariables(instance.getId());
              try {
                JsonNode parsed = objectMapper.readTree(outputJson);
                if (parsed.isObject()) {
                  parsed
                      .fields()
                      .forEachRemaining(entry -> variables.put(entry.getKey(), entry.getValue()));
                }
                variables.put(nodeId, parsed);
                context.setVariablesJson(objectMapper.writeValueAsString(variables));
                executionContextRepository.save(context);
              } catch (JsonProcessingException ignored) {
                // non-JSON node output: leave variables untouched
              }
            });
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

  private void guarded(Runnable action) {
    try {
      action.run();
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
  }

  @Override
  public void transition(WorkflowInstance instance, WorkflowState target, String action) {
    try {
      instance.transitionTo(target);
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
    log(instance, null, null, action, "State advanced to " + target);
  }

  @Override
  public void log(
      WorkflowInstance instance, String nodeId, String nodeType, String action, String payload) {
    ExecutionLog log =
        new ExecutionLog(
            instance.getTenantId(), instance.getId(), nodeId, nodeType, action, payload);
    executionLogRepository.save(log);
  }

  private NodeExecution findNodeExecution(UUID instanceId, String nodeId) {
    return nodeExecutionRepository
        .findByWorkflowInstanceIdAndNodeId(instanceId, nodeId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Node execution not found."));
  }
}
