package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The synchronous first attempt: exactly one call, stable idempotency key, payload validation. */
class WebhookInvokerTest {

  private final WebhookClient client = mock(WebhookClient.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebhookInvoker invoker =
      new WebhookInvoker(client, new NodeConfigReader(objectMapper), objectMapper);

  private final WorkflowInstance instance =
      new WorkflowInstance(
          UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
  private final NodeExecution execution =
      new NodeExecution(instance.getTenantId(), instance.getId(), "hook", "WEBHOOK");

  private NodeDefinition node(String configJson) {
    return new NodeDefinition(
        instance.getTenantId(), UUID.randomUUID(), "hook", NodeType.WEBHOOK, "hook", configJson, 0);
  }

  @Test
  void performsExactlyOneAttemptWithTheStableIdempotencyKeyAndConfiguredTimeout() {
    NodeDefinition node =
        node(
            "{\"url\":\"https://hooks.example.com\",\"payload\":\"{\\\"x\\\":1}\","
                + "\"maxAttempts\":3,\"timeoutSeconds\":7}");
    when(client.attempt(
            eq("https://hooks.example.com"),
            eq("{\"x\":1}"),
            anyString(),
            eq(Duration.ofSeconds(7))))
        .thenReturn(WebhookAttemptResult.httpError(500, "", null));

    WebhookAttemptResult result = invoker.invoke(instance, node, execution);

    assertThat(result.statusCode()).isEqualTo(500);
    verify(client, times(1))
        .attempt(
            eq("https://hooks.example.com"),
            eq("{\"x\":1}"),
            eq(instance.getId() + ":hook:" + execution.getId()),
            eq(Duration.ofSeconds(7)));
  }

  @Test
  void missingUrlIsAPermanentValidationFailure() {
    WebhookAttemptResult result = invoker.invoke(instance, node("{}"), execution);

    assertThat(result.success()).isFalse();
    assertThat(result.statusCode()).isEqualTo(400);
    assertThat(result.error().orElseThrow()).contains("requires a url");
    verify(client, never()).attempt(anyString(), anyString(), anyString(), any(Duration.class));
  }

  @Test
  void malformedJsonPayloadIsAPermanentValidationFailure() {
    NodeDefinition node = node("{\"url\":\"https://hooks.example.com\",\"payload\":\"not-json\"}");

    WebhookAttemptResult result = invoker.invoke(instance, node, execution);

    assertThat(result.statusCode()).isEqualTo(400);
    assertThat(result.error().orElseThrow()).contains("not valid JSON");
    verify(client, never()).attempt(anyString(), anyString(), anyString(), any(Duration.class));
  }

  @Test
  void payloadOverOneMegabyteIsRejectedBeforeSending() throws Exception {
    String hugePayload = "{\"data\":\"" + "a".repeat(WebhookInvoker.MAX_PAYLOAD_BYTES) + "\"}";
    String configJson =
        objectMapper
            .createObjectNode()
            .put("url", "https://hooks.example.com")
            .put("payload", hugePayload)
            .toString();

    WebhookAttemptResult result = invoker.invoke(instance, node(configJson), execution);

    assertThat(result.statusCode()).isEqualTo(413);
    assertThat(result.error().orElseThrow()).contains("1 MB");
    verify(client, never()).attempt(anyString(), anyString(), anyString(), any(Duration.class));
  }

  @Test
  void defaultPayloadIsAnEmptyJsonObject() {
    when(client.attempt(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    invoker.invoke(instance, node("{\"url\":\"https://hooks.example.com\"}"), execution);

    verify(client).attempt(eq("https://hooks.example.com"), eq("{}"), anyString(), any());
  }

  @Test
  void exposedIdentityFieldsMatchTheDeliveryRow() {
    NodeDefinition node = node("{\"url\":\"https://hooks.example.com\",\"payload\":\"{}\"}");

    assertThat(invoker.targetUrl(node)).isEqualTo("https://hooks.example.com");
    assertThat(invoker.payload(node)).isEqualTo("{}");
    assertThat(invoker.idempotencyKey(instance, node, execution))
        .isEqualTo(instance.getId() + ":hook:" + execution.getId());
  }
}
