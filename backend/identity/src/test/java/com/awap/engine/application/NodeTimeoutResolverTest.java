package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeTimeoutResolverTest {

  private final UUID tenantId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final NodeTimeoutResolver resolver =
      new NodeTimeoutResolver(
          new NodeConfigReader(new ObjectMapper()), new NodeTimeoutSettings(30, 3600, 8, 64));

  private NodeDefinition node(String nodeId, NodeType type, String configJson) {
    return new NodeDefinition(tenantId, versionId, nodeId, type, nodeId, configJson, 0);
  }

  @Test
  void fallsBackToTheConfiguredDefault() {
    NodeTimeoutResolution resolution =
        resolver.resolve(node("hook", NodeType.WEBHOOK, "{\"url\":\"https://x\"}"));

    assertThat(resolution.enforcement()).isEqualTo(NodeTimeoutEnforcement.ENFORCED);
    assertThat(resolution.timeout()).contains(Duration.ofSeconds(30));
    assertThat(resolution.ignoredConfiguredTimeout()).isEmpty();
  }

  @Test
  void explicitTimeoutSecondsWins() {
    NodeTimeoutResolution resolution =
        resolver.resolve(
            node("hook", NodeType.WEBHOOK, "{\"url\":\"https://x\",\"timeoutSeconds\":5}"));

    assertThat(resolution.timeout()).contains(Duration.ofSeconds(5));
  }

  @Test
  void zeroIsRejected() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    node("hook", NodeType.WEBHOOK, "{\"url\":\"https://x\",\"timeoutSeconds\":0}")))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("timeoutSeconds=0")
        .satisfies(
            ex ->
                assertThat(((AwapException) ex).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void negativeIsRejected() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    node(
                        "hook",
                        NodeType.WEBHOOK,
                        "{\"url\":\"https://x\",\"timeoutSeconds\":-10}")))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("timeoutSeconds=-10");
  }

  @Test
  void absurdValueIsRejectedWithTheConfiguredBounds() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    node(
                        "hook",
                        NodeType.WEBHOOK,
                        "{\"url\":\"https://x\",\"timeoutSeconds\":86400}")))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("between 1 and 3600");
  }

  @Test
  void unsupportedNodeTypeIgnoresAConfiguredTimeout() {
    NodeTimeoutResolution resolution =
        resolver.resolve(node("start", NodeType.START, "{\"timeoutSeconds\":5}"));

    assertThat(resolution.enforcement()).isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
    assertThat(resolution.timeout()).isEmpty();
    assertThat(resolution.ignoredConfiguredTimeout()).contains(Duration.ofSeconds(5));
  }

  @Test
  void unsupportedNodeTypeWithoutConfigurationHasNothingToIgnore() {
    NodeTimeoutResolution resolution = resolver.resolve(node("end", NodeType.END, null));

    assertThat(resolution.timeout()).isEmpty();
    assertThat(resolution.ignoredConfiguredTimeout()).isEmpty();
  }

  @Test
  void malformedConfigFallsBackToTheDefault() {
    NodeTimeoutResolution resolution = resolver.resolve(node("hook", NodeType.WEBHOOK, "not-json"));

    assertThat(resolution.timeout()).contains(Duration.ofSeconds(30));
  }

  @Test
  void transactionBoundNodeKeepsBestEffortEnforcement() {
    NodeTimeoutResolution resolution =
        resolver.resolve(node("approve", NodeType.APPROVAL, "{\"timeoutSeconds\":2}"));

    assertThat(resolution.enforcement()).isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(resolution.timeout()).contains(Duration.ofSeconds(2));
    assertThat(resolution.isEnforced()).isFalse();
  }
}
