package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.webhook.application.InMemoryWebhookDeliveryStore;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.application.WebhookDeliveryService;
import com.awap.webhook.application.WebhookRetryPolicy;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** WEBHOOK node handover: synchronous first attempt, durable retry queue, permanent failures. */
class NodeExecutorWebhookHandoverTest {

  private final NodeExecutionRepository nodeExecutionRepository =
      mock(NodeExecutionRepository.class);
  private final WorkflowDefinitionService workflowDefinitionService =
      mock(WorkflowDefinitionService.class);
  private final TaskService taskService = mock(TaskService.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final WorkflowEnginePort port = mock(WorkflowEnginePort.class);
  private final WebhookInvoker webhookInvoker = mock(WebhookInvoker.class);
  private final InMemoryWebhookDeliveryStore store = new InMemoryWebhookDeliveryStore();
  private final WebhookRetryPolicy policy =
      new WebhookRetryPolicy(
          10, new long[] {1_000L, 5_000L, 25_000L, 120_000L, 600_000L}, 0.2, () -> 0.0);
  private final WebhookDeliveryService deliveryService =
      new WebhookDeliveryService(store, policy, 7, 25, Duration.ofSeconds(30));

  private final UUID tenantId = UUID.randomUUID();
  private NodeExecutor executor;

  @BeforeEach
  void setUp() {
    executor =
        new NodeExecutor(
            nodeExecutionRepository,
            workflowDefinitionService,
            new NodeConfigReader(new ObjectMapper().findAndRegisterModules()),
            taskService,
            userRepository,
            port,
            webhookInvoker,
            deliveryService);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.empty());
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private WorkflowInstance instance() {
    return new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
  }

  private NodeDefinition node(String configJson) {
    return new NodeDefinition(
        tenantId, UUID.randomUUID(), "hook", NodeType.WEBHOOK, "hook", configJson, 0);
  }

  private NodeExecution savedExecution() {
    ArgumentCaptor<NodeExecution> captor = ArgumentCaptor.forClass(NodeExecution.class);
    verify(nodeExecutionRepository, atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  private void stubNodeIdentity() {
    when(webhookInvoker.targetUrl(any())).thenReturn("https://hooks.example.com/path");
    when(webhookInvoker.payload(any())).thenReturn("{\"a\":1}");
  }

  @Test
  void retryableFailureHandsTheDeliveryToTheDurableQueueAndWaits() {
    WorkflowInstance instance = instance();
    stubNodeIdentity();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.httpError(503, "", null));

    executor.executeNode(
        instance, node("{\"url\":\"https://hooks.example.com/path\",\"maxAttempts\":4}"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    NodeExecution execution = savedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(store.allRows()).hasSize(1);
    WebhookDelivery delivery = store.allRows().get(0);
    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getMaxAttempts()).isEqualTo(4);
    assertThat(delivery.getTenantId()).isEqualTo(tenantId);
    assertThat(delivery.getWorkflowInstanceId()).isEqualTo(instance.getId());
    assertThat(delivery.getNodeExecutionId()).isEqualTo(execution.getId());
    assertThat(delivery.getTargetUrl()).isEqualTo("https://hooks.example.com/path");
    assertThat(delivery.getPayloadJson()).isEqualTo("{\"a\":1}");
    assertThat(delivery.getIdempotencyKey())
        .isEqualTo(instance.getId() + ":hook:" + execution.getId());
    assertThat(delivery.getNextAttemptAt()).isBetween(Instant.now(), Instant.now().plusSeconds(2));
    verify(port)
        .log(eq(instance), eq("hook"), eq("WEBHOOK"), eq("NODE_RETRY_SCHEDULED"), anyString());
    verify(port, never()).transition(any(), eq(WorkflowState.FAILED), anyString());
    verify(port, never()).advanceFrom(any(), anyString());
  }

  @Test
  void successfulFirstAttemptCreatesNoRetryRow() {
    WorkflowInstance instance = instance();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.success(201, "{\"ok\":true}"));

    executor.executeNode(instance, node("{\"url\":\"https://hooks.example.com\"}"));

    NodeExecution execution = savedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(execution.getOutputJson()).isEqualTo("{\"ok\":true}");
    assertThat(store.allRows()).isEmpty();
    verify(port).advanceFrom(instance, "hook");
  }

  @Test
  void permanentClientErrorBecomesDeadAndFailsTheInstance() {
    WorkflowInstance instance = instance();
    stubNodeIdentity();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.httpError(403, "", null));

    executor.executeNode(instance, node("{\"url\":\"https://hooks.example.com/path\"}"));

    assertThat(store.allRows()).hasSize(1);
    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD);
    assertThat(store.allRows().get(0).getLastStatusCode()).hasValue(403);
    NodeExecution execution = savedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  @Test
  void validationFailureWithoutATargetUrlFailsWithoutAPersistedRow() {
    WorkflowInstance instance = instance();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.rejected(400, "requires a url"));
    when(webhookInvoker.targetUrl(any())).thenReturn(null);

    executor.executeNode(instance, node("{}"));

    assertThat(store.allRows()).isEmpty();
    assertThat(savedExecution().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  @Test
  void transportFailureIsAlsoHandedOverToTheQueue() {
    WorkflowInstance instance = instance();
    stubNodeIdentity();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.transportFailure("connect timed out"));

    executor.executeNode(instance, node("{\"url\":\"https://hooks.example.com/path\"}"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    assertThat(store.allRows()).hasSize(1);
    assertThat(store.allRows().get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(store.allRows().get(0).getLastError()).contains("connect timed out");
  }

  @Test
  void aWaitingNodeIsNotInvokedASecondTime() {
    WorkflowInstance instance = instance();
    stubNodeIdentity();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenReturn(WebhookAttemptResult.httpError(500, "", null));
    java.util.concurrent.atomic.AtomicReference<NodeExecution> stored =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(
            invocation -> {
              NodeExecution execution = invocation.getArgument(0);
              stored.set(execution);
              return execution;
            });
    NodeDefinition node = node("{\"url\":\"https://hooks.example.com/path\"}");

    executor.executeNode(instance, node);
    executor.executeNode(instance, node);

    verify(webhookInvoker, org.mockito.Mockito.times(1)).invoke(any(), any(), any());
    assertThat(store.allRows()).hasSize(1);
    assertThat(store.allRows().get(0).getAttempts()).isEqualTo(1);
  }
}
