package com.awap.engine.application;

/**
 * Raised when the bounded timeout executor is saturated.
 *
 * <p>Rejection is a controlled failure: the node is recorded as timed out instead of blocking the
 * caller or growing the queue without limit.
 */
public class NodeTimeoutRejectedException extends RuntimeException {

  private final String nodeType;
  private final String nodeId;

  public NodeTimeoutRejectedException(String nodeType, String nodeId, Throwable cause) {
    super(
        "Node '"
            + nodeId
            + "' of type "
            + nodeType
            + " could not be scheduled: the bounded node timeout executor is saturated.",
        cause);
    this.nodeType = nodeType;
    this.nodeId = nodeId;
  }

  public String nodeType() {
    return nodeType;
  }

  public String nodeId() {
    return nodeId;
  }
}
