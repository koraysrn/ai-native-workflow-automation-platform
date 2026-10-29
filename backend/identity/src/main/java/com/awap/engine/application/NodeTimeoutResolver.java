package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeDefinition;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;

/**
 * Resolves the effective timeout of a node from its {@code timeoutSeconds} config, falling back to
 * {@code awap.engine.node-timeout.default-seconds}.
 *
 * <p>An explicitly configured value must be a positive number of seconds within the configured
 * bound; zero, negative or absurd values are rejected with a validation error instead of being
 * clamped, so a broken workflow definition cannot silently run with an unintended budget.
 */
final class NodeTimeoutResolver {

  private final NodeConfigReader configReader;
  private final NodeTimeoutSettings settings;

  NodeTimeoutResolver(NodeConfigReader configReader, NodeTimeoutSettings settings) {
    this.configReader = configReader;
    this.settings = settings;
  }

  NodeTimeoutResolution resolve(NodeDefinition node) {
    NodeTimeoutEnforcement enforcement = NodeTimeoutPolicy.enforcementFor(node.getNodeType());
    OptionalLong configured = configReader.readConfiguredTimeoutSeconds(node);
    Optional<Duration> configuredTimeout =
        configured.isPresent()
            ? Optional.of(Duration.ofSeconds(configured.getAsLong()))
            : Optional.empty();

    if (enforcement == NodeTimeoutEnforcement.UNSUPPORTED) {
      return NodeTimeoutResolution.unsupported(configuredTimeout);
    }
    if (configured.isEmpty()) {
      return NodeTimeoutResolution.supported(enforcement, settings.defaultTimeout());
    }
    long seconds = configured.getAsLong();
    if (!settings.isWithinBounds(seconds)) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Node '"
              + node.getNodeId()
              + "' has an invalid timeoutSeconds="
              + seconds
              + "; it must be between "
              + NodeTimeoutSettings.MIN_NODE_TIMEOUT_SECONDS
              + " and "
              + settings.maxSeconds()
              + " seconds.");
    }
    return NodeTimeoutResolution.supported(enforcement, Duration.ofSeconds(seconds));
  }
}
