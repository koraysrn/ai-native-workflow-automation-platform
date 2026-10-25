package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
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
  }

  /** Follows every matching transition out of {@code fromNodeId}, executing or merging targets. */
  void advanceFrom(WorkflowInstance instance, String fromNodeId) {
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
                        target -> {
                          if (target.getNodeType() == NodeType.MERGE) {
                            reportBranchMerge(instance, target);
                          } else {
                            port.executeNode(instance, target);
                          }
                        },
                        () -> {
                          throw new AwapException(
                              ErrorCode.VALIDATION_FAILED,
                              HttpStatus.BAD_REQUEST,
                              "Transition target node not found: " + transition.getToNodeId());
                        }));
  }

  /** A blank condition is an unconditional transition; otherwise the SpEL result must be TRUE. */
  boolean evaluateCondition(TransitionDefinition transition, Map<String, Object> variables) {
    if (transition.getConditionExpression() == null
        || transition.getConditionExpression().isBlank()) {
      return true;
    }
    Object result = spelExpressionService.evaluate(transition.getConditionExpression(), variables);
    return Boolean.TRUE.equals(result);
  }

  /**
   * Records that a parallel branch reached the merge gateway. The join fires (executes the MERGE
   * node) only once every branch opened by the matching PARALLEL gateway has reported.
   */
  void reportBranchMerge(WorkflowInstance instance, NodeDefinition mergeNode) {
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
    if (parallel.getMergedBranchCount() >= parallel.getBranchTokenCount()) {
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
