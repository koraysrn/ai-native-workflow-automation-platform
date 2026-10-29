package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the outcome of a node timeout.
 *
 * <p>The retry policy is applied here, separately from the failure policy of {@link NodeExecutor}:
 * a timeout is retried only while the attempt budget allows it, and the operation is idempotent —
 * an execution that is already {@link NodeExecutionStatus#TIMED_OUT} is never re-scheduled or
 * counted twice.
 */
final class NodeTimeoutHandler {

  private static final Logger log = LoggerFactory.getLogger(NodeTimeoutHandler.class);

  static final String ACTION = "NODE_TIMED_OUT";

  private final NodeExecutionRepository nodeExecutionRepository;
  private final NodeConfigReader configReader;
  private final WorkflowEnginePort port;
  private final NodeTimeoutMetrics metrics;
  private final ObjectMapper objectMapper;

  NodeTimeoutHandler(
      NodeExecutionRepository nodeExecutionRepository,
      NodeConfigReader configReader,
      WorkflowEnginePort port,
      NodeTimeoutMetrics metrics,
      ObjectMapper objectMapper) {
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.configReader = configReader;
    this.port = port;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
  }

  /** True when the exception chain was caused by a database-level timeout. */
  static boolean isTimeoutFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof QueryTimeoutException
          || current instanceof SQLTimeoutException
          || current instanceof org.springframework.dao.QueryTimeoutException
          || current instanceof java.util.concurrent.TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  void handle(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution,
      Duration elapsed,
      String reason,
      boolean replayMode) {
    if (execution.getStatus() == NodeExecutionStatus.TIMED_OUT) {
      log.debug(
          "Node execution {} is already TIMED_OUT; duplicate timeout handling skipped",
          execution.getId());
      return;
    }

    execution.markTimedOut(reason);
    int maxAttempts = configReader.readMaxAttempts(node);
    boolean retryable =
        !replayMode
            && NodeTimeoutPolicy.enforcementFor(node.getNodeType())
                != NodeTimeoutEnforcement.UNSUPPORTED
            && execution.getAttempt() < maxAttempts;
    if (retryable) {
      long backoffMs = configReader.readRetryBackoffMs(node);
      execution.scheduleResume(Instant.now().plusMillis(Math.max(1, backoffMs)));
    }
    nodeExecutionRepository.save(execution);

    Duration timeout = resolution.timeout().orElse(Duration.ZERO);
    log.atWarn()
        .addKeyValue("workflowInstanceId", instance.getId())
        .addKeyValue("nodeId", node.getNodeId())
        .addKeyValue("nodeType", node.getNodeType().name())
        .addKeyValue("enforcement", resolution.enforcement().name())
        .addKeyValue("timeoutMs", timeout.toMillis())
        .addKeyValue("elapsedMs", elapsed.toMillis())
        .addKeyValue("attempt", execution.getAttempt())
        .addKeyValue("maxAttempts", maxAttempts)
        .addKeyValue("retryScheduled", retryable)
        .addKeyValue("reason", reason)
        .log("Node execution timed out");
    port.log(
        instance,
        node.getNodeId(),
        node.getNodeType().name(),
        ACTION,
        payload(resolution, elapsed, timeout, execution, maxAttempts, retryable, reason));

    if (retryable) {
      return;
    }
    metrics.recordTimeoutFailure(node.getNodeType().name());
    if (instance.getState() != WorkflowState.FAILED) {
      port.transition(instance, WorkflowState.FAILED, ACTION);
    }
  }

  private String payload(
      NodeTimeoutResolution resolution,
      Duration elapsed,
      Duration timeout,
      NodeExecution execution,
      int maxAttempts,
      boolean retryable,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("enforcement", resolution.enforcement().name());
    payload.put("hardGuarantee", resolution.enforcement().hardGuarantee());
    payload.put("timeoutSeconds", timeout.toSeconds());
    payload.put("elapsedMs", elapsed.toMillis());
    payload.put("attempt", execution.getAttempt());
    payload.put("maxAttempts", maxAttempts);
    payload.put("retryScheduled", retryable);
    payload.put("reason", reason);
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      return "timeoutSeconds=" + timeout.toSeconds() + " elapsedMs=" + elapsed.toMillis();
    }
  }
}
