package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Executes a single workflow node, dispatching on its type. */
final class NodeExecutor {

  private final NodeExecutionRepository nodeExecutionRepository;
  private final WorkflowDefinitionService workflowDefinitionService;
  private final NodeConfigReader configReader;
  private final TaskService taskService;
  private final UserRepository userRepository;
  private final WorkflowEnginePort port;

  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port) {
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.workflowDefinitionService = workflowDefinitionService;
    this.configReader = configReader;
    this.taskService = taskService;
    this.userRepository = userRepository;
    this.port = port;
  }

  /** Locates the START node and executes it, kicking off the workflow. */
  void executeStartNode(WorkflowInstance instance) {
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

  void executeNode(WorkflowInstance instance, NodeDefinition node) {
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
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        port.advanceFrom(instance, node.getNodeId());
      }
      case END -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        port.transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
      }
      case FORM -> {
        execution.waitForExternal();
        nodeExecutionRepository.save(execution);
        instance.transitionTo(WorkflowState.WAITING);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_WAITING", null);
      }
      case DELAY -> {
        long delaySeconds = configReader.readDelaySeconds(node);
        execution.scheduleResume(Instant.now().plusSeconds(delaySeconds));
        execution.waitForExternal();
        nodeExecutionRepository.save(execution);
        instance.transitionTo(WorkflowState.WAITING);
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_DELAYED",
            "delaySeconds=" + delaySeconds);
      }
      case PARALLEL -> {
        int branches =
            (int)
                transitionsOf(instance).stream()
                    .filter(transition -> transition.getFromNodeId().equals(node.getNodeId()))
                    .count();
        execution.openBranches(Math.max(branches, 1));
        execution.setJoinNodeId(configReader.readJoinNodeId(node));
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_FORKED",
            "branches=" + branches);
        port.advanceFrom(instance, node.getNodeId());
      }
      case MERGE -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        port.advanceFrom(instance, node.getNodeId());
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
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_WAITING", null);
      }
    }
  }

  void resumeFailedNodes(WorkflowInstance instance) {
    nodeExecutionRepository
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.FAILED)
        .forEach(
            execution ->
                nodesOf(instance).stream()
                    .filter(node -> node.getNodeId().equals(execution.getNodeId()))
                    .findFirst()
                    .ifPresent(node -> executeNode(instance, node)));
  }

  private UUID resolveAssignee(WorkflowInstance instance, NodeDefinition node) {
    for (String approver : configReader.readApprovers(node)) {
      if (!approver.startsWith("role:")) {
        continue;
      }
      String role = approver.substring("role:".length());
      try {
        return userRepository
            .findFirstByTenantIdAndRole(instance.getTenantId(), Role.valueOf(role))
            .map(User::getId)
            .orElse(null);
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
    return null;
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
