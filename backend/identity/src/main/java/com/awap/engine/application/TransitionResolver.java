package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Resolves workflow transitions and merge gateways after a node completes. */
final class TransitionResolver {

  private final NodeExecutionRepository nodeExecutionRepository;
  private final WorkflowDefinitionService workflowDefinitionService;
  private final ExecutionContextRepository executionContextRepository;
  private final SpelExpressionService spelExpressionService;
  private final ObjectMapper objectMapper;
  private final WorkflowEnginePort port;
  private final NodeConfigReader configReader;

  TransitionResolver(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      ExecutionContextRepository executionContextRepository,
      SpelExpressionService spelExpressionService,
      ObjectMapper objectMapper,
      WorkflowEnginePort port) {
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.workflowDefinitionService = workflowDefinitionService;
    this.executionContextRepository = executionContextRepository;
    this.spelExpressionService = spelExpressionService;
    this.objectMapper = objectMapper;
    this.port = port;
    this.configReader = new NodeConfigReader(objectMapper);
  }

  /** Follows the outgoing transitions of {@code fromNodeId} according to the node's semantics. */
  void advanceFrom(WorkflowInstance instance, String fromNodeId) {
    if (instance.getState() == WorkflowState.FAILED
        || instance.getState() == WorkflowState.CANCELLED
        || instance.getState() == WorkflowState.COMPLETED) {
      return;
    }
    Map<String, Object> variables = readVariables(instance.getId());
    List<NodeDefinition> nodes = nodesOf(instance);
    if (nodes.isEmpty()) {
      // A definition without a persisted graph has nothing to advance (empty draft/test fixture).
      return;
    }
    NodeDefinition from =
        nodes.stream()
            .filter(node -> node.getNodeId().equals(fromNodeId))
            .findFirst()
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        HttpStatus.BAD_REQUEST,
                        "Source node not found: " + fromNodeId));

    List<TransitionDefinition> outgoing =
        transitionsOf(instance).stream()
            .filter(transition -> transition.getFromNodeId().equals(fromNodeId))
            .toList();

    List<TransitionDefinition> selected = selectTransitions(from, outgoing, variables);
    if (selected.isEmpty()
        && (from.getNodeType() == NodeType.DECISION || from.getNodeType() == NodeType.CONDITION)) {
      throw new AwapException(
          ErrorCode.WORKFLOW_STATE_CONFLICT,
          HttpStatus.CONFLICT,
          "No matching " + from.getNodeType() + " branch for node " + fromNodeId + ".");
    }

    selected.forEach(transition -> follow(instance, nodes, transition));
  }

  /** DECISION takes the first matching branch; CONDITION follows every matching branch. */
  private List<TransitionDefinition> selectTransitions(
      NodeDefinition from, List<TransitionDefinition> outgoing, Map<String, Object> variables) {
    if (from.getNodeType() == NodeType.DECISION) {
      return outgoing.stream()
          .filter(transition -> evaluateCondition(from, transition, variables))
          .findFirst()
          .map(List::of)
          .orElse(List.of());
    }
    if (from.getNodeType() == NodeType.CONDITION) {
      return outgoing.stream()
          .filter(transition -> evaluateCondition(from, transition, variables))
          .toList();
    }
    return outgoing.stream()
        .filter(transition -> evaluateCondition(from, transition, variables))
        .toList();
  }

  private void follow(
      WorkflowInstance instance, List<NodeDefinition> nodes, TransitionDefinition transition) {
    NodeDefinition target =
        nodes.stream()
            .filter(node -> node.getNodeId().equals(transition.getToNodeId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        HttpStatus.BAD_REQUEST,
                        "Transition target node not found: " + transition.getToNodeId()));
    if (target.getNodeType().isMergeGateway()) {
      reportBranchMerge(instance, target);
    } else {
      port.executeNode(instance, target);
    }
  }

  boolean evaluateCondition(TransitionDefinition transition, Map<String, Object> variables) {
    return evaluateCondition(null, transition, variables);
  }

  private boolean evaluateCondition(
      NodeDefinition from, TransitionDefinition transition, Map<String, Object> variables) {
    String expression = transition.getConditionExpression();
    if ((expression == null || expression.isBlank()) && from != null) {
      expression = configReader.readExpression(from).orElse(null);
    }
    if (expression == null || expression.isBlank()) {
      return true;
    }
    Object result = spelExpressionService.evaluate(expression, variables);
    return Boolean.TRUE.equals(result);
  }

  /**
   * Records that a parallel branch reached the merge gateway. The join fires (executes the MERGE
   * node) only once every branch opened by the matching parallel gateway has reported.
   */
  void reportBranchMerge(WorkflowInstance instance, NodeDefinition mergeNode) {
    if (instance.getState() == WorkflowState.FAILED
        || instance.getState() == WorkflowState.CANCELLED
        || instance.getState() == WorkflowState.COMPLETED) {
      return;
    }
    NodeExecution parallel =
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndJoinNodeId(instance.getId(), mergeNode.getNodeId())
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        HttpStatus.BAD_REQUEST,
                        "Merge node has no matching parallel gateway: " + mergeNode.getNodeId()));
    parallel.recordBranchMerge();
    nodeExecutionRepository.save(parallel);
    if (parallel.getMergedBranchCount() >= parallel.getBranchTokenCount()
        && instance.getState() != WorkflowState.FAILED) {
      port.executeNode(instance, mergeNode);
    }
  }

  /** Re-drives completed nodes after a pause so skipped outgoing transitions are picked up. */
  void resumeCompletedNodes(WorkflowInstance instance) {
    nodeExecutionRepository
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.COMPLETED)
        .forEach(execution -> advanceFrom(instance, execution.getNodeId()));
  }

  Map<String, Object> readVariables(UUID instanceId) {
    return executionContextRepository
        .findByWorkflowInstanceId(instanceId)
        .map(
            context -> {
              try {
                return objectMapper.readValue(
                    context.getVariablesJson(), new TypeReference<Map<String, Object>>() {});
              } catch (JsonProcessingException ex) {
                return Map.<String, Object>of();
              }
            })
        .orElse(Map.of());
  }

  private List<NodeDefinition> nodesOf(WorkflowInstance instance) {
    return workflowDefinitionService.nodesOf(
        instance.getWorkflowDefinitionId(), instance.getWorkflowVersion());
  }

  private List<TransitionDefinition> transitionsOf(WorkflowInstance instance) {
    return workflowDefinitionService.transitionsOf(
        instance.getWorkflowDefinitionId(), instance.getWorkflowVersion());
  }
}
