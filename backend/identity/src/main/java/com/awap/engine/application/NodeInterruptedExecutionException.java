package com.awap.engine.application;

/**
 * Raised when the thread waiting for a node's result is interrupted (typically during shutdown).
 *
 * <p>The interrupt flag of the waiting thread is restored by the watchdog before this exception is
 * thrown, so nothing swallows the interruption.
 */
public class NodeInterruptedExecutionException extends RuntimeException {

  public NodeInterruptedExecutionException(String nodeType, String nodeId, Throwable cause) {
    super("Waiting for node '" + nodeId + "' of type " + nodeType + " was interrupted.", cause);
  }
}
