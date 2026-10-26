package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookInvokerTest {

  private final WebhookClient client = mock(WebhookClient.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebhookInvoker invoker =
      new WebhookInvoker(client, new NodeConfigReader(objectMapper), millis -> {});

  @Test
  void retriesTransientFailuresWithIdempotencyKeyAndConfiguredTimeout() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
    NodeExecution execution =
        new NodeExecution(instance.getTenantId(), instance.getId(), "hook", "WEBHOOK");
    NodeDefinition node =
        new NodeDefinition(
            instance.getTenantId(),
            UUID.randomUUID(),
            "hook",
            NodeType.WEBHOOK,
            "hook",
            "{\"url\":\"https://hooks.example.com\",\"payload\":\"{\\\"x\\\":1}\","
                + "\"maxAttempts\":3,\"timeoutSeconds\":7}",
            0);
    when(client.post(
            eq("https://hooks.example.com"),
            eq("{\"x\":1}"),
            anyString(),
            eq(Duration.ofSeconds(7))))
        .thenThrow(new IllegalStateException("timeout"))
        .thenThrow(new IllegalStateException("timeout"))
        .thenReturn("{\"ok\":true}");

    String result = invoker.invoke(instance, node, execution);

    assertThat(result).isEqualTo("{\"ok\":true}");
    verify(client, times(3))
        .post(
            eq("https://hooks.example.com"),
            eq("{\"x\":1}"),
            eq(instance.getId() + ":hook:" + execution.getId()),
            eq(Duration.ofSeconds(7)));
  }

  @Test
  void missingUrlFailsFastWithoutCallingTheClient() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
    NodeExecution execution =
        new NodeExecution(instance.getTenantId(), instance.getId(), "hook", "WEBHOOK");
    NodeDefinition node =
        new NodeDefinition(
            instance.getTenantId(), UUID.randomUUID(), "hook", NodeType.WEBHOOK, "hook", "{}", 0);

    assertThatThrownBy(() -> invoker.invoke(instance, node, execution))
        .isInstanceOf(com.awap.common.api.AwapException.class);
    verify(client, times(0))
        .post(anyString(), anyString(), anyString(), eq(Duration.ofSeconds(10)));
  }

  @Test
  void jitteredBackoffIsAppliedBetweenAttempts() {
    List<Long> sleeps = new ArrayList<>();
    WebhookInvoker sleepingInvoker =
        new WebhookInvoker(client, new NodeConfigReader(objectMapper), sleeps::add);
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
    NodeExecution execution =
        new NodeExecution(instance.getTenantId(), instance.getId(), "hook", "WEBHOOK");
    NodeDefinition node =
        new NodeDefinition(
            instance.getTenantId(),
            UUID.randomUUID(),
            "hook",
            NodeType.WEBHOOK,
            "hook",
            "{\"url\":\"https://hooks.example.com\",\"maxAttempts\":3}",
            0);
    when(client.post(anyString(), anyString(), anyString(), eq(Duration.ofSeconds(10))))
        .thenThrow(new IllegalStateException("timeout"));

    assertThatThrownBy(() -> sleepingInvoker.invoke(instance, node, execution))
        .isInstanceOf(IllegalStateException.class);

    assertThat(sleeps).hasSize(2);
    assertThat(sleeps.get(0)).isBetween(1_000L, 1_200L);
    assertThat(sleeps.get(1)).isBetween(5_000L, 6_000L);
  }
}
