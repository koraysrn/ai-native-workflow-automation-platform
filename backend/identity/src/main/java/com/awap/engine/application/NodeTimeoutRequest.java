package com.awap.engine.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable description of the timeout that must be applied to a piece of node work.
 *
 * @param nodeType node type name, used as the {@code type} metric tag
 * @param nodeId node identifier within the workflow definition
 * @param enforcement guarantee the engine can provide
 * @param timeout effective budget, strictly positive
 */
public record NodeTimeoutRequest(
    String nodeType, String nodeId, NodeTimeoutEnforcement enforcement, Duration timeout) {

  public NodeTimeoutRequest {
    Objects.requireNonNull(nodeId, "nodeId");
    enforcement = enforcement == null ? NodeTimeoutEnforcement.UNSUPPORTED : enforcement;
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("A node timeout must be positive but was " + timeout);
    }
  }

  public static NodeTimeoutRequest of(
      String nodeType, String nodeId, NodeTimeoutResolution resolution) {
    Duration timeout =
        resolution.timeout().orElseThrow(() -> new IllegalStateException("No timeout resolved."));
    return new NodeTimeoutRequest(nodeType, nodeId, resolution.enforcement(), timeout);
  }
}
