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
import com.awap.engine.interfaces.dto.ExecutionLogResponse;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import com.awap.workflow.domain.WorkflowDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow engine: starts instances, executes nodes deterministically and audits every step. */
@Service
public class WorkflowEngineService {

  private final WorkflowInstanceRepository instanceRepository;
  private final ExecutionLogRepository executionLogRepository;
  private final WorkflowDefinitionService workflowDefinitionService;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final ExecutionContextRepository executionContextRepository;
  private final TaskService taskService;
  private final SpelExpressionService spelExpressionService;
  private final ObjectMapper objectMapper;
  private final UserRepository userRepository;
  private final OutboxEventPublisher outboxEventPublisher;

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
      OutboxEventPublisher outboxEventPublisher) {
    this.instanceRepository = instanceRepository;
    this.executionLogRepository = executionLogRepository;
    this.workflowDefinitionService = workflowDefinitionService;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.executionContextRepository = executionContextRepository;
    this.taskService = taskService;
    this.spelExpressionService = spelExpressionService;
    this.objectMapper = objectMapper;
    this.userRepository = userRepository;
    this.outboxEventPublisher = outboxEventPublisher;
  }

  @Transactional
  public WorkflowInstanceResponse start(UUID tenantId, StartWorkflowRequest request) {
    if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
      var existing =
          instanceRepository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
      if (existing.isPresent()) {
        return toResponse(existing.get());
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

    executeStartNode(instance);
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
    advanceFrom(instance, nodeId);
    return toResponse(instance);
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
    resumeFailedNodes(instance);
    return toResponse(instance);
  }

  @Transactional
  public WorkflowInstanceResponse replay(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    // Deterministic replay: re-drive failed nodes idempotently from the recorded
    // execution state. External side effects are not repeated (no external-call
    // node types exist in Phase 0).
    transition(instance, WorkflowState.RUNNING, "WORKFLOW_REPLAYED");
    resumeFailedNodes(instance);
    return toResponse(instance);
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

  @Transactional
  public WorkflowInstanceResponse pause(UUID tenantId, UUID id) {
    WorkflowInstance instance = find(tenantId, id);
    guarded(instance::pause);
    log(instance, null, null, "WORKFLOW_PAUSED", "Workflow instance paused.");
    outboxEventPublisher.publish(
        tenantId, "WORKFLOW", instance.getId(), "WORKFLOW_PAUSED", Map.of("state", "PAUSED"));
    return toResponse(instance);
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
    resumeCompletedNodes(instance);
    return toResponse(instance);
  }

  private void executeStartNode(WorkflowInstance instance) {
    NodeDefinition startNode =
        nodesOf(instance).stream()
            .filter(node -> node.getNodeType() == NodeType.START)
            .findFirst()
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.VALIDATION_FAILED,
                        HttpStatus.BAD_REQUEST,
                        "Workflow has no START node."));
    executeNode(instance, startNode);
  }

  private void executeNode(WorkflowInstance instance, NodeDefinition node) {
    if (instance.getState() == WorkflowState.PAUSED) {
      return;
    }
    if (instance.getState() != WorkflowState.COMPLETED
        && instance.getState() != WorkflowState.CANCELLED
        && instance.getState() != WorkflowState.FAILED) {
      instance.transitionTo(WorkflowState.RUNNING);
    }
    NodeExecution execution =
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndNodeId(instance.getId(), node.getNodeId())
            .orElseGet(
                () ->
                    nodeExecutionRepository.save(
                        new NodeExecution(
                            instance.getTenantId(),
                            instance.getId(),
                            node.getNodeId(),
                            node.getNodeType().name())));

    if (execution.getStatus() == NodeExecutionStatus.COMPLETED
        || execution.getStatus() == NodeExecutionStatus.WAITING) {
      return;
    }

    execution.start();
    switch (node.getNodeType()) {
      case START -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        advanceFrom(instance, node.getNodeId());
      }
      case END -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
      }
      case FORM -> {
        execution.waitForExternal();
        nodeExecutionRepository.save(execution);
        instance.transitionTo(WorkflowState.WAITING);
        log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_WAITING", null);
      }
      case APPROVAL, TASK -> {
        UUID assigneeId = resolveAssignee(instance, node);
        TaskRequest taskRequest =
            new TaskRequest(
                node.getName(),
                "Auto-created by workflow node " + node.getNodeId(),
                instance.getId(),
                execution.getId(),
                assigneeId);
        taskService.create(instance.getTenantId(), taskRequest);
        execution.waitForExternal();
        nodeExecutionRepository.save(execution);
        instance.transitionTo(WorkflowState.WAITING);
        log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_WAITING", null);
      }
    }
  }

  private void resumeFailedNodes(WorkflowInstance instance) {
    nodeExecutionRepository
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.FAILED)
        .forEach(
            execution ->
                nodesOf(instance).stream()
                    .filter(node -> node.getNodeId().equals(execution.getNodeId()))
                    .findFirst()
                    .ifPresent(node -> executeNode(instance, node)));
  }

  /**
   * After a pause, nodes that completed while the instance was paused may have had their outgoing
   * transitions skipped. Re-driving them is idempotent: targets that are already COMPLETED or
   * WAITING are ignored by executeNode.
   */
  private void resumeCompletedNodes(WorkflowInstance instance) {
    nodeExecutionRepository
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.COMPLETED)
        .forEach(execution -> advanceFrom(instance, execution.getNodeId()));
  }

  private UUID resolveAssignee(WorkflowInstance instance, NodeDefinition node) {
    String configJson = node.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      JsonNode config = objectMapper.readTree(configJson);
      JsonNode approvers = config.get("approvers");
      if (approvers != null && approvers.isArray()) {
        for (JsonNode approver : approvers) {
          String value = approver.asText(null);
          if (value != null && value.startsWith("role:")) {
            String role = value.substring("role:".length());
            return userRepository
                .findFirstByTenantIdAndRole(instance.getTenantId(), Role.valueOf(role))
                .map(User::getId)
                .orElse(null);
          }
        }
      }
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      return null;
    }
    return null;
  }

  private void advanceFrom(WorkflowInstance instance, String fromNodeId) {
    Map<String, Object> variables = readVariables(instance.getId());
    List<NodeDefinition> nodes = nodesOf(instance);

    transitionsOf(instance).stream()
        .filter(transition -> transition.getFromNodeId().equals(fromNodeId))
        .filter(transition -> evaluateCondition(transition, variables))
        .forEach(
            transition ->
                nodes.stream()
                    .filter(node -> node.getNodeId().equals(transition.getToNodeId()))
                    .findFirst()
                    .ifPresentOrElse(
                        target -> executeNode(instance, target),
                        () -> {
                          throw new AwapException(
                              ErrorCode.VALIDATION_FAILED,
                              HttpStatus.BAD_REQUEST,
                              "Transition target node not found: " + transition.getToNodeId());
                        }));
  }

  private boolean evaluateCondition(
      TransitionDefinition transition, Map<String, Object> variables) {
    if (transition.getConditionExpression() == null
        || transition.getConditionExpression().isBlank()) {
      return true;
    }
    Object result = spelExpressionService.evaluate(transition.getConditionExpression(), variables);
    return Boolean.TRUE.equals(result);
  }

  private List<NodeDefinition> nodesOf(WorkflowInstance instance) {
    return workflowDefinitionService.nodesOf(
        instance.getWorkflowDefinitionId(), instance.getWorkflowVersion());
  }

  private List<TransitionDefinition> transitionsOf(WorkflowInstance instance) {
    return workflowDefinitionService.transitionsOf(
        instance.getWorkflowDefinitionId(), instance.getWorkflowVersion());
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

  private void mergeOutputIntoContext(WorkflowInstance instance, String nodeId, String outputJson) {
    if (outputJson == null || outputJson.isBlank()) {
      return;
    }
    executionContextRepository
        .findByWorkflowInstanceId(instance.getId())
        .ifPresent(
            context -> {
              Map<String, Object> variables = readVariables(instance.getId());
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

  private Map<String, Object> readVariables(UUID instanceId) {
    return executionContextRepository
        .findByWorkflowInstanceId(instanceId)
        .map(ExecutionContext::getVariablesJson)
        .map(
            json -> {
              try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
              } catch (JsonProcessingException ex) {
                return Map.<String, Object>of();
              }
            })
        .orElse(Map.of());
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

  private void transition(WorkflowInstance instance, WorkflowState target, String action) {
    try {
      instance.transitionTo(target);
    } catch (IllegalStateException ex) {
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.CONFLICT, ex.getMessage());
    }
    log(instance, null, null, action, "State advanced to " + target);
  }

  private void log(
      WorkflowInstance instance, String nodeId, String nodeType, String action, String payload) {
    ExecutionLog log =
        new ExecutionLog(
            instance.getTenantId(), instance.getId(), nodeId, nodeType, action, payload);
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
