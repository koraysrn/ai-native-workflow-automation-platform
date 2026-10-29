package com.awap.engine.application;

import com.awap.ai.application.AiNodeService;
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
import com.awap.script.application.ScriptNodeService;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.application.WebhookDeliveryService;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Executes a single workflow node, dispatching on its type.
 *
 * <p>Timeout handling is delegated to {@link NodeTimeoutGuard}. The measured region contains only
 * the node's own work: advancing to the next node happens after the watchdog returns so a slow
 * downstream chain is never attributed to this node.
 */
final class NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

  private final NodeExecutionRepository nodeExecutionRepository;
  private final WorkflowDefinitionService workflowDefinitionService;
  private final NodeConfigReader configReader;
  private final TaskService taskService;
  private final UserRepository userRepository;
  private final WorkflowEnginePort port;
  private final WebhookInvoker webhookInvoker;
  private final NodeTimeoutGuard timeoutGuard;
  private final WebhookDeliveryService webhookDeliveryService;
  private final AiNodeService aiNodeService;
  private final ScriptNodeService scriptNodeService;

  /** Test/backward-compatible constructor: WEBHOOK/AI/SCRIPT nodes fail closed without adapters. */
  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port) {
    this(
        nodeExecutionRepository,
        workflowDefinitionService,
        configReader,
        taskService,
        userRepository,
        port,
        null,
        null,
        null,
        null,
        null);
  }

  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port,
      WebhookInvoker webhookInvoker) {
    this(
        nodeExecutionRepository,
        workflowDefinitionService,
        configReader,
        taskService,
        userRepository,
        port,
        webhookInvoker,
        null,
        null,
        null,
        null);
  }

  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port,
      WebhookInvoker webhookInvoker,
      NodeTimeoutGuard timeoutGuard) {
    this(
        nodeExecutionRepository,
        workflowDefinitionService,
        configReader,
        taskService,
        userRepository,
        port,
        webhookInvoker,
        timeoutGuard,
        null,
        null,
        null);
  }

  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port,
      WebhookInvoker webhookInvoker,
      WebhookDeliveryService webhookDeliveryService) {
    this(
        nodeExecutionRepository,
        workflowDefinitionService,
        configReader,
        taskService,
        userRepository,
        port,
        webhookInvoker,
        null,
        webhookDeliveryService,
        null,
        null);
  }

  NodeExecutor(
      NodeExecutionRepository nodeExecutionRepository,
      WorkflowDefinitionService workflowDefinitionService,
      NodeConfigReader configReader,
      TaskService taskService,
      UserRepository userRepository,
      WorkflowEnginePort port,
      WebhookInvoker webhookInvoker,
      NodeTimeoutGuard timeoutGuard,
      WebhookDeliveryService webhookDeliveryService,
      AiNodeService aiNodeService,
      ScriptNodeService scriptNodeService) {
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.workflowDefinitionService = workflowDefinitionService;
    this.configReader = configReader;
    this.taskService = taskService;
    this.userRepository = userRepository;
    this.port = port;
    this.webhookInvoker = webhookInvoker;
    this.timeoutGuard = timeoutGuard;
    this.webhookDeliveryService = webhookDeliveryService;
    this.aiNodeService = aiNodeService;
    this.scriptNodeService = scriptNodeService;
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
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        HttpStatus.BAD_REQUEST,
                        "Workflow has no START node."));
    executeNode(instance, startNode);
  }

  void executeNode(WorkflowInstance instance, NodeDefinition node) {
    executeNode(instance, node, false);
  }

  void executeNode(WorkflowInstance instance, NodeDefinition node, boolean replayMode) {
    if (instance.getState() == WorkflowState.PAUSED) {
      return;
    }
    if (instance.getState() == WorkflowState.COMPLETED
        || instance.getState() == WorkflowState.CANCELLED
        || instance.getState() == WorkflowState.FAILED) {
      return;
    }
    instance.transitionTo(WorkflowState.RUNNING);
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

    // Replay guarantee: an external call that already produced an output is reused instead of being
    // sent again. A failed external call is not retried during replay either.
    if (replayMode && node.getNodeType().isExternalCall()) {
      if (execution.getOutputJson() != null) {
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_REPLAYED",
            "output replayed");
        port.advanceFrom(instance, node.getNodeId());
      } else {
        execution.fail("External call was not repeated during replay.");
        nodeExecutionRepository.save(execution);
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_REPLAY_SKIPPED",
            "external call was not repeated during replay");
        if (instance.getState() != WorkflowState.FAILED) {
          port.transition(instance, WorkflowState.FAILED, "REPLAY_EXTERNAL_CALL_SUPPRESSED");
        }
      }
      return;
    }

    if (execution.getStatus() == NodeExecutionStatus.COMPLETED
        || execution.getStatus() == NodeExecutionStatus.WAITING) {
      return;
    }

    execution.start();
    NodeTimeoutResolution resolution = timeoutGuard == null ? null : timeoutGuard.resolve(node);
    try {
      Optional<String> advanceTo = dispatch(instance, node, execution, resolution);
      advanceTo.ifPresent(nodeId -> port.advanceFrom(instance, nodeId));
    } catch (NodeTimeoutException ex) {
      handleTimeout(
          instance, node, execution, resolution, ex.elapsed(), ex.getMessage(), replayMode);
    } catch (NodeTimeoutRejectedException ex) {
      handleTimeout(
          instance, node, execution, resolution, Duration.ZERO, ex.getMessage(), replayMode);
    } catch (NodeInterruptedExecutionException ex) {
      handleTimeout(
          instance, node, execution, resolution, Duration.ZERO, ex.getMessage(), replayMode);
      throw ex;
    } catch (RuntimeException ex) {
      if (timeoutGuard != null && timeoutGuard.isTimeoutFailure(ex)) {
        timeoutGuard.recordDatabaseTimeout(node, resolution);
        handleTimeout(
            instance,
            node,
            execution,
            resolution,
            Duration.ZERO,
            "Database operation timed out: " + ex.getMessage(),
            replayMode);
      } else {
        handleFailure(instance, node, execution, ex, replayMode);
      }
    }
  }

  /**
   * Runs the node work under its timeout policy. Only {@link NodeTimeoutEnforcement#BEST_EFFORT}
   * nodes are measured here; {@link NodeTimeoutEnforcement#ENFORCED} nodes measure their external
   * call inside {@link #executeWebhookNode} so no transaction-bound work ever leaves the caller
   * thread.
   */
  private Optional<String> dispatch(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution) {
    if (timeoutGuard == null) {
      return dispatchNode(instance, node, execution, resolution);
    }
    return timeoutGuard.measureNodeExecution(
        node, resolution, () -> dispatchNode(instance, node, execution, resolution));
  }

  /** Node-type dispatch. Returns the node id to advance from, or empty when the node waits/ends. */
  private Optional<String> dispatchNode(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution) {
    return switch (node.getNodeType()) {
      case START -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        yield Optional.of(node.getNodeId());
      }
      case END -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        port.transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
        yield Optional.empty();
      }
      case FORM -> {
        waitForExternal(instance, node, execution, "NODE_WAITING");
        yield Optional.empty();
      }
      case DELAY -> {
        long delaySeconds = configReader.readDelaySeconds(node);
        execution.scheduleResume(Instant.now().plusSeconds(delaySeconds));
        waitForExternal(instance, node, execution, "NODE_DELAYED");
        yield Optional.empty();
      }
      case PARALLEL, PARALLEL_GATEWAY -> {
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
        yield Optional.of(node.getNodeId());
      }
      case MERGE, MERGE_GATEWAY -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        yield Optional.of(node.getNodeId());
      }
      case DECISION, CONDITION -> {
        execution.complete("{}");
        nodeExecutionRepository.save(execution);
        port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", null);
        yield Optional.of(node.getNodeId());
      }
      case WEBHOOK -> executeWebhookNode(instance, node, execution, resolution);
      case SUB_PROCESS -> {
        String definitionId = configReader.readSubProcessDefinitionId(node);
        if (definitionId == null) {
          throw new AwapException(
              ErrorCode.WORKFLOW_INVALID_DEFINITION,
              HttpStatus.BAD_REQUEST,
              "SUB_PROCESS node '" + node.getNodeId() + "' requires workflowDefinitionId.");
        }
        waitForExternal(instance, node, execution, "NODE_WAITING");
        port.startSubProcess(instance, node, execution);
        yield Optional.empty();
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
        waitForExternal(instance, node, execution, "NODE_WAITING");
        yield Optional.empty();
      }
      case AI -> {
        if (aiNodeService == null) {
          throw new AwapException(
              ErrorCode.GENERIC_INTERNAL_ERROR,
              HttpStatus.NOT_IMPLEMENTED,
              "AI node execution is not enabled in this deployment.");
        }
        AiNodeService.AiAction action;
        try {
          action = AiNodeService.AiAction.valueOf(configReader.readAiAction(node));
        } catch (IllegalArgumentException ex) {
          throw new AwapException(
              ErrorCode.WORKFLOW_INVALID_DEFINITION,
              HttpStatus.BAD_REQUEST,
              "AI node '" + node.getNodeId() + "' has an unsupported action.");
        }
        String prompt = configReader.readAiPrompt(node).orElse("");
        AiNodeService.AiNodeResult result =
            aiNodeService.evaluate(
                instance.getTenantId(), instance.getId(), node.getNodeId(), action, prompt);
        if (result.requiresHumanApproval()) {
          waitForExternal(instance, node, execution, "AI_HITL_REQUIRED");
          yield Optional.empty();
        }
        execution.complete(result.output());
        nodeExecutionRepository.save(execution);
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_COMPLETED",
            result.output());
        yield Optional.of(node.getNodeId());
      }
      case SCRIPT -> {
        if (scriptNodeService == null) {
          throw new AwapException(
              ErrorCode.GENERIC_INTERNAL_ERROR,
              HttpStatus.NOT_IMPLEMENTED,
              "SCRIPT node execution is not enabled in this deployment.");
        }
        String script =
            configReader
                .readScript(node)
                .orElseThrow(
                    () ->
                        new AwapException(
                            ErrorCode.WORKFLOW_INVALID_DEFINITION,
                            HttpStatus.BAD_REQUEST,
                            "SCRIPT node '" + node.getNodeId() + "' requires a script."));
        ScriptNodeService.ScriptResult result =
            scriptNodeService.execute(
                instance.getTenantId(),
                instance.getId(),
                node.getNodeId(),
                script,
                configReader.readContextJson(node));
        execution.complete(result.output());
        nodeExecutionRepository.save(execution);
        port.log(
            instance,
            node.getNodeId(),
            node.getNodeType().name(),
            "NODE_COMPLETED",
            result.output());
        yield Optional.of(node.getNodeId());
      }
    };
  }

  private void waitForExternal(
      WorkflowInstance instance, NodeDefinition node, NodeExecution execution, String action) {
    execution.waitForExternal();
    nodeExecutionRepository.save(execution);
    instance.transitionTo(WorkflowState.WAITING);
    port.log(instance, node.getNodeId(), node.getNodeType().name(), action, null);
  }

  /**
   * WEBHOOK semantics: the first attempt is synchronous and runs inside the enforced timeout (only
   * the outbound HTTP call is interrupt-safe). Retryable failures are handed over to the durable
   * delivery queue (the node waits; the scheduler drives the remaining attempts) instead of being
   * retried in-thread. Permanent failures and exhausted budgets fail the node and instance.
   */
  private Optional<String> executeWebhookNode(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution) {
    if (webhookInvoker == null) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.SERVICE_UNAVAILABLE,
          "WEBHOOK executor is not configured.");
    }
    WebhookAttemptResult attempt =
        timeoutGuard == null
            ? webhookInvoker.invoke(instance, node, execution)
            : timeoutGuard.measureExternalCall(
                node, resolution, () -> webhookInvoker.invoke(instance, node, execution));
    if (attempt.success()) {
      String output = attempt.responseBodyOrEmptyObject();
      execution.complete(output);
      nodeExecutionRepository.save(execution);
      port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_COMPLETED", output);
      return Optional.of(node.getNodeId());
    }

    String error = errorOf(attempt);
    int maxAttempts = configReader.readMaxAttempts(node);
    String targetUrl = webhookInvoker.targetUrl(node);
    if (webhookDeliveryService == null
        || targetUrl == null
        || webhookDeliveryService.policy().isPermanent(attempt.statusCode())) {
      if (webhookDeliveryService != null && targetUrl != null) {
        webhookDeliveryService.recordPermanentFailure(
            pendingDelivery(instance, node, execution, targetUrl, maxAttempts, attempt, error),
            Instant.now());
      }
      failWebhookNode(instance, node, execution, error);
      return Optional.empty();
    }

    WebhookDelivery delivery =
        webhookDeliveryService.scheduleRetryAfterFailure(
            pendingDelivery(instance, node, execution, targetUrl, maxAttempts, attempt, error),
            Instant.now());
    handOverToScheduler(instance, node, execution, delivery, maxAttempts);
    return Optional.empty();
  }

  private void handleTimeout(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution,
      Duration elapsed,
      String reason,
      boolean replayMode) {
    if (timeoutGuard == null) {
      handleFailure(
          instance,
          node,
          execution,
          new NodeTimeoutException(
              node.getNodeType().name(), node.getNodeId(), Duration.ZERO, elapsed),
          replayMode);
      return;
    }
    timeoutGuard.onTimeout(instance, node, execution, resolution, elapsed, reason, replayMode);
  }

  private void handOverToScheduler(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      WebhookDelivery delivery,
      int maxAttempts) {
    execution.waitForExternal();
    nodeExecutionRepository.save(execution);
    instance.transitionTo(WorkflowState.WAITING);
    port.log(
        instance,
        node.getNodeId(),
        node.getNodeType().name(),
        "NODE_RETRY_SCHEDULED",
        "attempt=1 maxAttempts="
            + maxAttempts
            + " deliveryId="
            + delivery.getId()
            + " nextAttemptAt="
            + delivery.getNextAttemptAt());
  }

  private WebhookDeliveryService.PendingDelivery pendingDelivery(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      String targetUrl,
      int maxAttempts,
      WebhookAttemptResult attempt,
      String error) {
    return new WebhookDeliveryService.PendingDelivery(
        instance.getTenantId(),
        instance.getId(),
        execution.getId(),
        node.getNodeId(),
        targetUrl,
        webhookInvoker.payload(node),
        WebhookDelivery.idempotencyKey(instance.getId(), node.getNodeId(), execution.getId()),
        1,
        maxAttempts,
        attempt.statusCode(),
        error);
  }

  private void failWebhookNode(
      WorkflowInstance instance, NodeDefinition node, NodeExecution execution, String error) {
    execution.fail(error);
    nodeExecutionRepository.save(execution);
    port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_FAILED", error);
    port.transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  private String errorOf(WebhookAttemptResult attempt) {
    if (attempt.error().isPresent()) {
      return attempt.error().get();
    }
    return attempt.transportFailure()
        ? "Webhook transport failure."
        : "Webhook target returned status " + attempt.statusCode() + ".";
  }

  private void handleFailure(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      RuntimeException ex,
      boolean replayMode) {
    execution.fail(ex.getMessage());
    int maxAttempts = configReader.readMaxAttempts(node);
    boolean retryable = isRetryable(ex);
    if (!replayMode && retryable && execution.getAttempt() < maxAttempts) {
      long backoffMs = configReader.readRetryBackoffMs(node);
      execution.scheduleResume(Instant.now().plusMillis(Math.max(1, backoffMs)));
      nodeExecutionRepository.save(execution);
      port.log(
          instance,
          node.getNodeId(),
          node.getNodeType().name(),
          "NODE_RETRY_SCHEDULED",
          "attempt=" + execution.getAttempt() + " maxAttempts=" + maxAttempts);
      return;
    }
    nodeExecutionRepository.save(execution);
    port.log(instance, node.getNodeId(), node.getNodeType().name(), "NODE_FAILED", ex.getMessage());
    port.transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  private boolean isRetryable(RuntimeException ex) {
    if (ex instanceof AwapException awap) {
      ErrorCode code = awap.errorCode();
      return code != ErrorCode.WORKFLOW_INVALID_DEFINITION
          && code != ErrorCode.VALIDATION_FAILED
          && code != ErrorCode.FORBIDDEN;
    }
    return true;
  }

  void resumeFailedNodes(WorkflowInstance instance) {
    resumeFailedNodes(instance, false);
  }

  void resumeFailedNodes(WorkflowInstance instance, boolean replayMode) {
    failedOrTimedOutExecutions(instance)
        .forEach(
            execution ->
                nodesOf(instance).stream()
                    .filter(node -> node.getNodeId().equals(execution.getNodeId()))
                    .findFirst()
                    .ifPresent(node -> executeNode(instance, node, replayMode)));
  }

  /** FAILED and TIMED_OUT executions are both re-drivable; the query stays the same for both. */
  private Stream<NodeExecution> failedOrTimedOutExecutions(WorkflowInstance instance) {
    return Stream.concat(
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.FAILED)
            .stream(),
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.TIMED_OUT)
            .stream());
  }

  /**
   * Deterministic replay/recovery: re-drives unfinished nodes from the recorded execution state.
   * External calls are never repeated; recorded outputs are replayed instead.
   */
  void replayFromRecordedState(WorkflowInstance instance) {
    List<NodeExecution> unfinished =
        nodeExecutionRepository
            .findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId())
            .stream()
            .filter(
                execution ->
                    execution.getStatus().isFailed()
                        || execution.getStatus() == NodeExecutionStatus.RUNNING
                        || execution.getStatus() == NodeExecutionStatus.PENDING)
            .toList();
    for (NodeExecution execution : unfinished) {
      if (execution.getStatus() == NodeExecutionStatus.RUNNING) {
        execution.resetForRetry();
        nodeExecutionRepository.save(execution);
      }
      nodesOf(instance).stream()
          .filter(node -> node.getNodeId().equals(execution.getNodeId()))
          .findFirst()
          .ifPresent(node -> executeNode(instance, node, true));
    }
    if (unfinished.isEmpty()) {
      log.debug("Replay for instance {} found no unfinished nodes", instance.getId());
    }
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
