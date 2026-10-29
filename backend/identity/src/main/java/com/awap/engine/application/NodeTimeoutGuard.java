package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point the engine uses for node timeouts: resolution, measurement/enforcement and
 * outcome persistence.
 *
 * <p>Where the timeout is applied matters. Only outbound, interrupt-safe work may be moved to
 * another thread; everything that touches the JPA transaction stays on the caller thread. The class
 * therefore exposes two measurement methods instead of one generic wrapper.
 */
public final class NodeTimeoutGuard {

  private static final Logger log = LoggerFactory.getLogger(NodeTimeoutGuard.class);

  private final NodeTimeoutResolver resolver;
  private final NodeExecutionWatchdog watchdog;
  private final NodeTimeoutHandler handler;
  private final NodeTimeoutMetrics metrics;

  public NodeTimeoutGuard(
      NodeTimeoutSettings settings,
      NodeExecutionWatchdog watchdog,
      NodeTimeoutMetrics metrics,
      NodeExecutionRepository nodeExecutionRepository,
      NodeConfigReader configReader,
      WorkflowEnginePort port,
      ObjectMapper objectMapper) {
    this.resolver = new NodeTimeoutResolver(configReader, settings);
    this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.handler =
        new NodeTimeoutHandler(nodeExecutionRepository, configReader, port, metrics, objectMapper);
  }

  /**
   * Resolves the budget and warns when a timeout was configured on a node type that cannot use it.
   */
  public NodeTimeoutResolution resolve(NodeDefinition node) {
    NodeTimeoutResolution resolution = resolver.resolve(node);
    if (resolution.ignoredConfiguredTimeout().isPresent()) {
      log.atWarn()
          .addKeyValue("nodeId", node.getNodeId())
          .addKeyValue("nodeType", node.getNodeType().name())
          .addKeyValue("enforcement", resolution.enforcement().name())
          .addKeyValue(
              "configuredTimeoutSeconds",
              resolution.ignoredConfiguredTimeout().orElseThrow().toSeconds())
          .log("Node timeout configuration ignored: the node type has no interruptible work");
    }
    return resolution;
  }

  /**
   * Measures a whole node dispatch. Only {@link NodeTimeoutEnforcement#BEST_EFFORT} nodes are
   * measured here, and they are never interrupted: the work owns the JPA transaction.
   */
  public <T> T measureNodeExecution(
      NodeDefinition node, NodeTimeoutResolution resolution, Callable<T> work) {
    if (resolution.enforcement() != NodeTimeoutEnforcement.BEST_EFFORT) {
      return call(work);
    }
    return watchdog.run(
        NodeTimeoutRequest.of(node.getNodeType().name(), node.getNodeId(), resolution), work);
  }

  /**
   * Enforces the budget on an external, interrupt-safe call (HTTP/LLM). Nothing that touches the
   * current transaction may be passed here.
   */
  public <T> T measureExternalCall(
      NodeDefinition node, NodeTimeoutResolution resolution, Callable<T> work) {
    if (resolution.enforcement() != NodeTimeoutEnforcement.ENFORCED) {
      return call(work);
    }
    return watchdog.run(
        NodeTimeoutRequest.of(node.getNodeType().name(), node.getNodeId(), resolution), work);
  }

  /** Records the timeout outcome, applying the retry policy separately and idempotently. */
  public void onTimeout(
      WorkflowInstance instance,
      NodeDefinition node,
      NodeExecution execution,
      NodeTimeoutResolution resolution,
      Duration elapsed,
      String reason,
      boolean replayMode) {
    handler.handle(instance, node, execution, resolution, elapsed, reason, replayMode);
  }

  public boolean isTimeoutFailure(RuntimeException failure) {
    return NodeTimeoutHandler.isTimeoutFailure(failure);
  }

  /** Records a timeout that surfaced as a database-level timeout exception. */
  public void recordDatabaseTimeout(NodeDefinition node, NodeTimeoutResolution resolution) {
    metrics.recordTimeout(node.getNodeType().name(), resolution.enforcement(), "database");
  }

  private <T> T call(Callable<T> work) {
    try {
      return work.call();
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Node work failed.", ex);
    }
  }
}
