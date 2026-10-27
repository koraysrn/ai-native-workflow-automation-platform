package com.awap.engine.application;

import java.time.Duration;
import java.util.Optional;

/**
 * Outcome of resolving the timeout of a single node.
 *
 * @param enforcement guarantee the engine can provide for the node type
 * @param timeout effective budget, empty only when the node type is {@link
 *     NodeTimeoutEnforcement#UNSUPPORTED}
 * @param ignoredConfiguredTimeout the timeout the workflow author configured on an unsupported node
 *     type; present so the engine can warn instead of silently dropping the setting
 */
public record NodeTimeoutResolution(
    NodeTimeoutEnforcement enforcement,
    Optional<Duration> timeout,
    Optional<Duration> ignoredConfiguredTimeout) {

  public NodeTimeoutResolution {
    enforcement = enforcement == null ? NodeTimeoutEnforcement.UNSUPPORTED : enforcement;
    timeout = timeout == null ? Optional.empty() : timeout;
    ignoredConfiguredTimeout =
        ignoredConfiguredTimeout == null ? Optional.empty() : ignoredConfiguredTimeout;
  }

  public static NodeTimeoutResolution supported(
      NodeTimeoutEnforcement enforcement, Duration timeout) {
    return new NodeTimeoutResolution(enforcement, Optional.of(timeout), Optional.empty());
  }

  public static NodeTimeoutResolution unsupported(Optional<Duration> ignoredConfiguredTimeout) {
    return new NodeTimeoutResolution(
        NodeTimeoutEnforcement.UNSUPPORTED, Optional.empty(), ignoredConfiguredTimeout);
  }

  public boolean isEnforced() {
    return enforcement == NodeTimeoutEnforcement.ENFORCED;
  }
}
