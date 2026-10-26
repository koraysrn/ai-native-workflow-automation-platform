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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Test/backward-compatible constructor without the webhook invoker (WEBHOOK fails closed). */
  WorkflowEngineService(
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
    this(
        instanceRepository,
        executionLogRepository,
        workflowDefinitionService,
        nodeExecutionRepository,
        executionContextRepository,
        taskService,
        spelExpressionService,
        objectMapper,
        userRepository,
        outboxEventPublisher,
        engineMapper,
        null);
  }

  @Autowired
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
      EngineMapper engineMapper,
      WebhookInvoker webhookInvoker) {
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
            this,
            webhookInvoker);
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
    publishAnalytics(instance, "WORKFLOW_STARTED", null, 0L);

    nodeExecutor.executeStartNode(instance);
    return engineMapper.toResponse(instance);
  }

  @Override
  @Transactional
  public void startSubProcess(
      WorkflowInstance parent, NodeDefinition node, NodeExecution execution) {
    String definitionId = configReader.readSubProcessDefinitionId(node);
    if (definitionId == null) {
      throw new AwapException(
          ErrorCode.WORKFLOW_INVALID_DEFINITION,
          HttpStatus.BAD_REQUEST,
          "SUB_PROCESS node '" + node.getNodeId() + "' requires workflowDefinitionId.");
    }
    UUID definitionUuid;
    try {
      definitionUuid = UUID.fromString(definitionId);
    } catch (IllegalArgumentException ex) {
      throw new AwapException(
          ErrorCode.WORKFLOW_INVALID_DEFINITION,
          HttpStatus.BAD_REQUEST,
          "SUB_PROCESS workflowDefinitionId is not a UUID: " + definitionId);
    }
    WorkflowDefinition definition =
        workflowDefinitionService.getPublished(parent.getTenantId(), definitionUuid);
    WorkflowInstance child =
        new WorkflowInstance(
            parent.getTenantId(),
            definition.getId(),
            definition.getCurrentVersion(),
            WorkflowState.CREATED,
            null,
            parent.getContextJson(),
            parent.getId(),
            node.getNodeId());
    instanceRepository.save(child);
    executionContextRepository.save(
        new ExecutionContext(parent.getTenantId(), child.getId(), parent.getContextJson()));
    child.transitionTo(WorkflowState.STARTED);
    log(child, null, null, "INSTANCE_STARTED", "Sub-process instance started.");
    publishAnalytics(child, "WORKFLOW_STARTED", null, 0L);
    nodeExecutor.executeStartNode(child);
  }

  /** Resumes the parent node when a sub-process instance reaches a terminal state. */
  @EventListener
  @Transactional
  public void onSubProcessCompleted(SubProcessCompletedEvent event) {
    WorkflowInstance parent =
        instanceRepository
            .findByIdAndTenantIdAndDeletedFalse(event.parentInstanceId(), event.tenantId())
            .orElse(null);
    if (parent == null) {
      return;
    }
    NodeExecution execution =
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndNodeId(parent.getId(), event.parentNodeId())
            .orElse(null);
    if (execution == null
        || execution.getStatus() == NodeExecutionStatus.COMPLETED
        || execution.getStatus() == NodeExecutionStatus.FAILED) {
      return;
    }
    if (event.success()) {
      execution.complete(
          "{\"subProcessInstanceId\":\""
              + event.childInstanceId()
              + "\",\"status\":\"COMPLETED\"}");
      nodeExecutionRepository.save(execution);
      mergeOutputIntoContext(parent, event.parentNodeId(), execution.getOutputJson());
      log(parent, event.parentNodeId(), "SUB_PROCESS", "NODE_COMPLETED", execution.getOutputJson());
      if (parent.getState() == WorkflowState.WAITING) {
        parent.transitionTo(WorkflowState.RUNNING);
      }
      transitionResolver.advanceFrom(parent, event.parentNodeId());
    } else {
      execution.fail("Sub-process " + event.childInstanceId() + " failed.");
      nodeExecutionRepository.save(execution);
      log(parent, event.parentNodeId(), "SUB_PROCESS", "NODE_FAILED", execution.getError());
      guarded(() -> transition(parent, WorkflowState.FAILED, "SUB_PROCESS_FAILED"));
    }
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
    prepareForReplay(instance, "WORKFLOW_REPLAYED");
    nodeExecutor.replayFromRecordedState(instance);
    return engineMapper.toResponse(instance);
  }

  /** Crash recovery: continues RUNNING/FAILED instances from the last durable execution state. */
  @Transactional
  public WorkflowInstanceResponse recover(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    prepareForReplay(instance, "WORKFLOW_RECOVERED");
    nodeExecutor.resumeFailedNodes(instance);
    return engineMapper.toResponse(instance);
  }

  private void prepareForReplay(WorkflowInstance instance, String action) {
    if (instance.getState() == WorkflowState.PAUSED) {
      guarded(instance::resume);
    }
    if (instance.getState() == WorkflowState.FAILED) {
      transition(instance, WorkflowState.RUNNING, action);
    } else if (instance.getState() == WorkflowState.WAITING) {
      instance.transitionTo(WorkflowState.RUNNING);
    }
    log(instance, null, null, action, "Deterministic replay from recorded execution state.");
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
   * Resumes a delayed node or retries a failed node whose scheduled instant has elapsed.
   * Idempotent: only executions with a pending resume instant are processed.
   */
  @Transactional
  public WorkflowInstanceResponse resumeDueNode(UUID tenantId, UUID instanceId, String nodeId) {
    WorkflowInstance instance = find(tenantId, instanceId);
    NodeExecution execution = findNodeExecution(instanceId, nodeId);
    if (execution.getResumeAt() == null) {
      return engineMapper.toResponse(instance);
    }
    if (execution.getStatus() == NodeExecutionStatus.WAITING) {
      execution.clearResumeAt();
      execution.complete("{}");
      nodeExecutionRepository.save(execution);
      log(instance, nodeId, execution.getNodeType(), "NODE_COMPLETED", null);
      transitionResolver.advanceFrom(instance, nodeId);
      return engineMapper.toResponse(instance);
    }
    if (execution.getStatus() == NodeExecutionStatus.FAILED) {
      execution.resetForRetry();
      nodeExecutionRepository.save(execution);
      if (instance.getState() == WorkflowState.FAILED) {
        transition(instance, WorkflowState.RUNNING, "NODE_RETRY_STARTED");
      }
      log(instance, nodeId, execution.getNodeType(), "NODE_RETRY_STARTED", null);
      workflowDefinitionService
          .nodesOf(instance.getWorkflowDefinitionId(), instance.getWorkflowVersion())
          .stream()
          .filter(node -> node.getNodeId().equals(nodeId))
          .findFirst()
          .ifPresent(node -> nodeExecutor.executeNode(instance, node));
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
    if (target == WorkflowState.COMPLETED || target == WorkflowState.FAILED) {
      long durationMs =
          Math.max(0L, Duration.between(instance.getCreatedAt(), Instant.now()).toMillis());
      publishAnalytics(
          instance,
          target == WorkflowState.COMPLETED ? "WORKFLOW_COMPLETED" : "WORKFLOW_FAILED",
          null,
          durationMs);
      notifyParent(instance, target == WorkflowState.COMPLETED);
    }
  }

  private void notifyParent(WorkflowInstance instance, boolean success) {
    if (instance.getParentInstanceId() == null || instance.getParentNodeId() == null) {
      return;
    }
    outboxEventPublisher.publish(
        instance.getTenantId(),
        "WORKFLOW",
        instance.getId(),
        "SUB_PROCESS_COMPLETED",
        new SubProcessCompletedEvent(
            instance.getTenantId(),
            instance.getParentInstanceId(),
            instance.getParentNodeId(),
            instance.getId(),
            success));
  }

  private void publishAnalytics(
      WorkflowInstance instance, String eventType, UUID taskId, long durationMs) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("tenantId", instance.getTenantId().toString());
    payload.put("eventType", eventType);
    payload.put("workflowInstanceId", instance.getId().toString());
    payload.put("taskId", taskId == null ? null : taskId.toString());
    payload.put("durationMs", durationMs);
    payload.put("timestamp", Instant.now().toEpochMilli());
    outboxEventPublisher.publish(
        instance.getTenantId(), "WORKFLOW", instance.getId(), "ANALYTICS_EVENT", payload);
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
