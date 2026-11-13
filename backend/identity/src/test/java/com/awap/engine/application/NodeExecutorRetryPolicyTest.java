package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** Generic per-node retry policy behaviour in the engine (issue #210). */
class NodeExecutorRetryPolicyTest {

  private final NodeExecutionRepository nodeExecutionRepository =
      mock(NodeExecutionRepository.class);
  private final WorkflowDefinitionService workflowDefinitionService =
      mock(WorkflowDefinitionService.class);
  private final TaskService taskService = mock(TaskService.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final WorkflowEnginePort port = mock(WorkflowEnginePort.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);

  private final UUID tenantId = UUID.randomUUID();
  private UUID currentInstanceId;

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
            null,
            null,
            null,
            null,
            null,
            outbox);
  }

  private WorkflowInstance instance(WorkflowState state) {
    WorkflowInstance instance =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, state, null, "{}");
    currentInstanceId = instance.getId();
    return instance;
  }

  private NodeDefinition node(NodeType type, String retryPolicyJson) {
    String config = retryPolicyJson == null ? "{}" : "{\"retryPolicy\":" + retryPolicyJson + "}";
    return new NodeDefinition(tenantId, UUID.randomUUID(), "node-1", type, "n1", config, 0);
  }

  private NodeExecution executionNode(String nodeId, int alreadyAttempted) {
    NodeExecution execution = new NodeExecution(tenantId, currentInstanceId, nodeId, "TASK");
    for (int i = 0; i < alreadyAttempted; i++) {
      execution.start();
    }
    return execution;
  }

  private void stubExecution(NodeExecution execution) {
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(
            currentInstanceId, execution.getNodeId()))
        .thenReturn(Optional.of(execution));
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void aTransientFailureSchedulesTheNextAttemptOutsideTheRequestThread() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node =
        node(NodeType.TASK, "{\"maxAttempts\":3,\"delaysMs\":[2000],\"jitterRatio\":0}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("provider 503"));
    Instant before = Instant.now();

    executor.executeNode(instance, node);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(execution.getAttempt()).isEqualTo(1);
    assertThat(execution.getResumeAt()).isNotNull().isAfterOrEqualTo(before.plusMillis(1_900));
    assertThat(execution.getResumeAt()).isBefore(before.plusSeconds(5));
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
    verify(port)
        .log(
            eq(instance),
            eq(node.getNodeId()),
            eq("TASK"),
            eq("NODE_RETRY_SCHEDULED"),
            anyString());
    verify(outbox, never()).publish(any(), any(), any(), any(), any());
  }

  @Test
  void exhaustingThePolicyDeadLettersTheNodeWithTheAttemptAndIdempotencyKey() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node = node(NodeType.TASK, "{\"maxAttempts\":1,\"delaysMs\":[1000]}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("still down"));

    executor.executeNode(instance, node);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(execution.getResumeAt()).isNull();
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
    ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
    verify(outbox)
        .publish(
            eq(tenantId), eq("WORKFLOW"), eq(instance.getId()), eq("NODE_DLQ"), payload.capture());
    assertThat(payload.getValue())
        .containsEntry("reason", "EXHAUSTED")
        .containsEntry("attempts", 1)
        .containsEntry("nodeId", node.getNodeId())
        .containsEntry("idempotencyKey", instance.getId() + ":" + node.getNodeId());
  }

  @Test
  void aPermanentErrorIsDeadLetteredWithoutRetry() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node = node(NodeType.TASK, "{\"maxAttempts\":5,\"delaysMs\":[1000]}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "invalid input"));

    executor.executeNode(instance, node);

    assertThat(execution.getResumeAt()).isNull();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload =
        ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    verify(outbox)
        .publish(
            eq(tenantId), eq("WORKFLOW"), eq(currentInstanceId), eq("NODE_DLQ"), payload.capture());
    assertThat(payload.getValue()).containsEntry("reason", "PERMANENT");
  }

  @Test
  void aConfiguredClassListCanMakeAnUnlistedErrorPermanent() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node =
        node(
            NodeType.TASK,
            "{\"maxAttempts\":3,\"delaysMs\":[1000],"
                + "\"retryableErrorClasses\":[\"TimeoutException\"]}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("unlisted"));

    executor.executeNode(instance, node);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload =
        ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    verify(outbox)
        .publish(
            eq(tenantId), eq("WORKFLOW"), eq(currentInstanceId), eq("NODE_DLQ"), payload.capture());
    assertThat(payload.getValue()).containsEntry("reason", "PERMANENT");
  }

  @Test
  void aPausedWorkflowDoesNotRetryOrExecuteTheNode() {
    WorkflowInstance instance = instance(WorkflowState.PAUSED);
    NodeDefinition node = node(NodeType.TASK, "{\"maxAttempts\":3,\"delaysMs\":[1000]}");

    executor.executeNode(instance, node);

    verifyNoInteractions(nodeExecutionRepository, taskService, outbox);
  }

  @Test
  void theIdempotencyKeyStaysStableAcrossAttemptsAndTheSameExecutionRowIsReused() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node = node(NodeType.TASK, "{\"maxAttempts\":3,\"delaysMs\":[1000]}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("transient"));
    String key = execution.idempotencyKey();

    executor.executeNode(instance, node);
    executor.executeNode(instance, node);

    assertThat(execution.getAttempt()).isEqualTo(2);
    assertThat(execution.idempotencyKey())
        .isEqualTo(key)
        .isEqualTo(currentInstanceId + ":" + node.getNodeId());
    verify(nodeExecutionRepository, org.mockito.Mockito.times(2)).save(execution);
  }

  static List<NodeType> failingNodeTypes() {
    return List.of(
        NodeType.TASK, NodeType.WEBHOOK, NodeType.AI, NodeType.SCRIPT, NodeType.SUB_PROCESS);
  }

  @ParameterizedTest
  @EnumSource(
      value = NodeType.class,
      names = {"TASK", "WEBHOOK", "AI", "SCRIPT", "SUB_PROCESS"})
  void everyExecutableNodeTypeHonoursThePolicyAndReachesTheNodeDlq(NodeType nodeType) {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node = node(nodeType, "{\"maxAttempts\":1,\"delaysMs\":[1000]}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    if (nodeType == NodeType.TASK) {
      when(taskService.create(any(), any())).thenThrow(new IllegalStateException("down"));
    }

    executor.executeNode(instance, node);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    verify(outbox)
        .publish(eq(tenantId), eq("WORKFLOW"), eq(currentInstanceId), eq("NODE_DLQ"), any());
    assertThat(failingNodeTypes()).contains(nodeType);
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
    return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
  }

  @Test
  void retryDelayNeverMovesBackwards() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node =
        node(NodeType.TASK, "{\"maxAttempts\":2,\"delaysMs\":[1000],\"jitterRatio\":0.5}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("down"));
    Instant before = Instant.now();

    executor.executeNode(instance, node);

    assertThat(Duration.between(before, execution.getResumeAt())).isPositive();
  }

  @Test
  void anInvalidPolicyFallsBackToDefaultsInsteadOfEscapingTheFailureHandler() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeDefinition node = node(NodeType.TASK, "{\"maxAttempts\":0}");
    NodeExecution execution = executionNode(node.getNodeId(), 0);
    stubExecution(execution);
    when(taskService.create(any(), any())).thenThrow(new IllegalStateException("down"));

    executor.executeNode(instance, node);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(execution.getResumeAt()).isNotNull();
  }
}
