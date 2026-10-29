package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

  @Test
  void readsWebhookAndSubProcessSettingsWithSafeDefaults() {
    NodeDefinition configured =
        node(
            "{\"url\":\"https://hooks.example.com\",\"payload\":\"{}\","
                + "\"timeoutSeconds\":3,\"maxAttempts\":4,\"retryBackoffMs\":250,"
                + "\"workflowDefinitionId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"version\":2,\"expression\":\"#amount > 10\"}");

    assertThat(reader.readWebhookUrl(configured)).isEqualTo("https://hooks.example.com");
    assertThat(reader.readWebhookPayload(configured)).isEqualTo("{}");
    assertThat(reader.readTimeoutSeconds(configured)).isEqualTo(3);
    assertThat(reader.readMaxAttempts(configured)).isEqualTo(4);
    assertThat(reader.readRetryBackoffMs(configured)).isEqualTo(250);
    assertThat(reader.readSubProcessDefinitionId(configured))
        .isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(reader.readSubProcessVersion(configured)).contains(2);
    assertThat(reader.readExpression(configured)).contains("#amount > 10");
  }

  @Test
  void readSubProcessVersionRejectsNonPositiveValues() {
    assertInvalidVersion("{\"version\":0}", "0");
    assertInvalidVersion("{\"version\":-1}", "-1");
  }

  @Test
  void readSubProcessVersionRejectsNonNumericValues() {
    assertInvalidVersion("{\"version\":\"latest\"}", "latest");
    assertInvalidVersion("{\"version\":1.5}", "1.5");
    assertInvalidVersion("{\"version\":\"\"}", "");
    assertInvalidVersion("{\"version\":{}}", "{}");
  }

  @Test
  void readSubProcessVersionAcceptsNumericStrings() {
    assertThat(reader.readSubProcessVersion(node("{\"version\":\"3\"}"))).contains(3);
  }

  private void assertInvalidVersion(String configJson, String rawValue) {
    assertThatThrownBy(() -> reader.readSubProcessVersion(node(configJson)))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.WORKFLOW_VERSION_INVALID);
              assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(ex.getMessage()).contains(rawValue);
            });
  }

  @Test
  void newSettingsFallBackOnMalformedConfig() {
    NodeDefinition broken = node("not-json");

    assertThat(reader.readWebhookUrl(broken)).isNull();
    assertThat(reader.readWebhookPayload(broken)).isEqualTo("{}");
    assertThat(reader.readTimeoutSeconds(broken)).isEqualTo(10);
    assertThat(reader.readMaxAttempts(broken)).isEqualTo(3);
    assertThat(reader.readRetryBackoffMs(broken)).isEqualTo(1000);
    assertThat(reader.readSubProcessDefinitionId(broken)).isNull();
    assertThat(reader.readSubProcessVersion(broken)).isEmpty();
    assertThat(reader.readExpression(broken)).isEmpty();
  }

  @Test
  void maxAttemptsIsClampedToTen() {
    assertThat(reader.readMaxAttempts(node("{\"maxAttempts\":99}"))).isEqualTo(10);
    assertThat(reader.readMaxAttempts(node("{\"maxAttempts\":0}"))).isEqualTo(1);
  }
}
