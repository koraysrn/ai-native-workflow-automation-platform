package com.awap.engine.application;

import java.time.Duration;

/**
 * Raised when a node exceeded its timeout budget.
 *
 * <p>Carries the measured elapsed time and the configured budget so the engine can persist a
 * structured {@code NODE_TIMED_OUT} record without parsing the message.
 */
public class NodeTimeoutException extends RuntimeException {

  /** Reason tag: the measured work exceeded the configured budget. */
  public static final String REASON_TIMEOUT = "timeout";

  /** Reason tag: the work was cancelled because the executor is shutting down. */
  public static final String REASON_CANCELLED = "cancelled";

  private final String nodeType;
  private final String nodeId;
  private final Duration timeout;
  private final Duration elapsed;
  private final String reason;

  public NodeTimeoutException(String nodeType, String nodeId, Duration timeout, Duration elapsed) {
    this(nodeType, nodeId, timeout, elapsed, REASON_TIMEOUT);
  }

  public NodeTimeoutException(
      String nodeType, String nodeId, Duration timeout, Duration elapsed, String reason) {
    super(message(nodeType, nodeId, timeout, elapsed, reason));
    this.nodeType = nodeType;
    this.nodeId = nodeId;
    this.timeout = timeout;
    this.elapsed = elapsed;
    this.reason = reason;
  }

  private static String message(
      String nodeType, String nodeId, Duration timeout, Duration elapsed, String reason) {
    String prefix = "Node '" + nodeId + "' of type " + nodeType;
    if (REASON_CANCELLED.equals(reason)) {
      return prefix
          + " was cancelled after "
          + elapsed.toMillis()
          + "ms because the node timeout executor is shutting down (budget "
          + timeout.toMillis()
          + "ms).";
    }
    return prefix
        + " exceeded its "
        + timeout.toMillis()
        + "ms timeout after "
        + elapsed.toMillis()
        + "ms.";
  }

  public String nodeType() {
    return nodeType;
  }

  public String nodeId() {
    return nodeId;
  }

  public Duration timeout() {
    return timeout;
  }

  public Duration elapsed() {
    return elapsed;
  }

  public String reason() {
    return reason;
  }
}
