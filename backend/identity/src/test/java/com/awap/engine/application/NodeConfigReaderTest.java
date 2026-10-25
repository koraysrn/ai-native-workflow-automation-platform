package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeConfigReaderTest {

  private NodeConfigReader reader;

  @BeforeEach
  void setUp() {
    reader = new NodeConfigReader(new ObjectMapper().findAndRegisterModules());
  }

  private NodeDefinition node(String configJson) {
    return new NodeDefinition(
        UUID.randomUUID(), UUID.randomUUID(), "node-1", NodeType.DELAY, "n1", configJson, 0);
  }

  @Test
  void readDelaySecondsReturnsConfiguredValue() {
    assertThat(reader.readDelaySeconds(node("{\"delaySeconds\":30}"))).isEqualTo(30);
  }

  @Test
  void readDelaySecondsDefaultsToZeroWhenMissing() {
    assertThat(reader.readDelaySeconds(node("{}"))).isZero();
  }

  @Test
  void readDelaySecondsDefaultsToZeroWhenMalformed() {
    assertThat(reader.readDelaySeconds(node("not-json"))).isZero();
  }

  @Test
  void readDelaySecondsDefaultsToZeroWhenBlank() {
    assertThat(reader.readDelaySeconds(node(null))).isZero();
    assertThat(reader.readDelaySeconds(node("  "))).isZero();
  }

  @Test
  void readJoinNodeIdReturnsConfiguredValue() {
    assertThat(reader.readJoinNodeId(node("{\"joinNodeId\":\"merge-1\"}"))).isEqualTo("merge-1");
  }

  @Test
  void readJoinNodeIdReturnsNullWhenMissingOrMalformed() {
    assertThat(reader.readJoinNodeId(node("{}"))).isNull();
    assertThat(reader.readJoinNodeId(node("not-json"))).isNull();
    assertThat(reader.readJoinNodeId(node(null))).isNull();
  }

  @Test
  void readApproversReturnsConfiguredValues() {
    assertThat(
            reader.readApprovers(node("{\"approvers\":[\"role:TENANT_ADMIN\",\"role:MANAGER\"]}")))
        .containsExactly("role:TENANT_ADMIN", "role:MANAGER");
  }

  @Test
  void readApproversReturnsEmptyWhenMissingOrMalformed() {
    assertThat(reader.readApprovers(node("{}"))).isEmpty();
    assertThat(reader.readApprovers(node("not-json"))).isEmpty();
    assertThat(reader.readApprovers(node(null))).isEmpty();
    assertThat(reader.readApprovers(node("{\"approvers\":\"not-an-array\"}"))).isEmpty();
  }

  @Test
  void readApproversSkipsNullEntries() {
    assertThat(reader.readApprovers(node("{\"approvers\":[null,\"role:TENANT_ADMIN\"]}")))
        .isEqualTo(List.of("role:TENANT_ADMIN"));
  }
}
