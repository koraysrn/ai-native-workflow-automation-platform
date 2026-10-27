package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class NodeExecutorTest {

  @Mock NodeExecutionRepository nodeExecutionRepository;
  @Mock WorkflowDefinitionService workflowDefinitionService;
  @Mock TaskService taskService;
  @Mock UserRepository userRepository;
  @Mock WorkflowEnginePort port;
  @Mock WebhookInvoker webhookInvoker;

  private NodeExecutor executor;
  private final UUID tenantId = UUID.randomUUID();
  private final UUID workflowDefinitionId = UUID.randomUUID();

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
            webhookInvoker);
  }

  private WorkflowInstance instance(WorkflowState state) {
    return new WorkflowInstance(tenantId, workflowDefinitionId, 1, state, null, "{}");
  }

  private NodeDefinition node(String nodeId, NodeType type, String configJson) {
    return new NodeDefinition(tenantId, UUID.randomUUID(), nodeId, type, nodeId, configJson, 0);
  }

  private void stubFreshExecution() {
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.empty());
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private NodeExecution capturedExecution() {
    ArgumentCaptor<NodeExecution> captor = ArgumentCaptor.forClass(NodeExecution.class);
    verify(nodeExecutionRepository, atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void startNodeCompletesAndAdvances() {
    WorkflowInstance instance = instance(WorkflowState.STARTED);
    stubFreshExecution();

    executor.executeNode(instance, node("start", NodeType.START, null));

    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    verify(port).advanceFrom(instance, "start");
  }

  @Test
  void endNodeCompletesWorkflow() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("end", NodeType.END, null));

    verify(port).transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");
  }

  @Test
  void formNodeWaitsForExternalInput() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("form", NodeType.FORM, null));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
  }

  @Test
  void delayNodeSchedulesResumeAndWaits() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("delay", NodeType.DELAY, "{\"delaySeconds\":30}"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    assertThat(capturedExecution().getResumeAt()).isNotNull();
  }

  @Test
  void delayNodeWithMalformedConfigFallsBackToZeroDelay() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("delay", NodeType.DELAY, "not-json"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
  }

  @Test
  void parallelNodeOpensBranchesAndAdvances() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(workflowDefinitionService.transitionsOf(workflowDefinitionId, 1))
        .thenReturn(
            List.of(
                new TransitionDefinition(tenantId, UUID.randomUUID(), "fork", "b1", null),
                new TransitionDefinition(tenantId, UUID.randomUUID(), "fork", "b2", null)));

    executor.executeNode(instance, node("fork", NodeType.PARALLEL, "{\"joinNodeId\":\"merge-1\"}"));

    assertThat(capturedExecution().getBranchTokenCount()).isEqualTo(2);
    assertThat(capturedExecution().getJoinNodeId()).isEqualTo("merge-1");
    verify(port).advanceFrom(instance, "fork");
  }

  @Test
  void mergeNodeCompletesAndAdvances() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("merge", NodeType.MERGE, null));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    verify(port).advanceFrom(instance, "merge");
  }

  @Test
  void taskNodeCreatesTaskAndWaits() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(
        instance, node("approve", NodeType.TASK, "{\"approvers\":[\"role:TENANT_ADMIN\"]}"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    verify(taskService).create(eq(tenantId), any(TaskRequest.class));
  }

  @Test
  void resolveAssigneeWithUnknownRoleReturnsNullWithoutCrashing() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(
        instance, node("approve", NodeType.APPROVAL, "{\"approvers\":[\"role:UNKNOWN_ROLE\"]}"));

    ArgumentCaptor<TaskRequest> captor = ArgumentCaptor.forClass(TaskRequest.class);
    verify(taskService).create(eq(tenantId), captor.capture());
    assertThat(captor.getValue().assigneeId()).isNull();
  }

  @Test
  void resolveAssigneeReturnsMatchingUserForRoleApprover() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    User admin = new User(tenantId, "a@b.c", "x", "Ada", Role.TENANT_ADMIN);
    when(userRepository.findFirstByTenantIdAndRole(tenantId, Role.TENANT_ADMIN))
        .thenReturn(Optional.of(admin));

    executor.executeNode(
        instance, node("approve", NodeType.APPROVAL, "{\"approvers\":[\"role:TENANT_ADMIN\"]}"));

    ArgumentCaptor<TaskRequest> captor = ArgumentCaptor.forClass(TaskRequest.class);
    verify(taskService).create(eq(tenantId), captor.capture());
    assertThat(captor.getValue().assigneeId()).isEqualTo(admin.getId());
  }

  @Test
  void pausedInstanceSkipsExecution() {
    WorkflowInstance instance = instance(WorkflowState.PAUSED);

    executor.executeNode(instance, node("start", NodeType.START, null));

    verify(nodeExecutionRepository, org.mockito.Mockito.never())
        .findByWorkflowInstanceIdAndNodeId(any(), anyString());
  }

  @Test
  void alreadyCompletedExecutionIsSkipped() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeExecution done = new NodeExecution(tenantId, instance.getId(), "start", "START");
    done.start();
    done.complete("{}");
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.of(done));

    executor.executeNode(instance, node("start", NodeType.START, null));

    verify(nodeExecutionRepository, org.mockito.Mockito.never()).save(any(NodeExecution.class));
  }

  @Test
  void decisionNodeCompletesAndAdvances() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(instance, node("decide", NodeType.DECISION, "{}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    verify(port).advanceFrom(instance, "decide");
  }

  @Test
  void webhookNodeInvokesExternalCallAndCompletesWithResponse() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(webhookInvoker.invoke(any(), any(), any())).thenReturn("{\"ok\":true}");

    executor.executeNode(
        instance, node("hook", NodeType.WEBHOOK, "{\"url\":\"https://hooks.example.com\"}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(capturedExecution().getOutputJson()).isEqualTo("{\"ok\":true}");
    verify(port).advanceFrom(instance, "hook");
  }

  @Test
  void webhookFailureSchedulesRetryWhenAttemptsRemain() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenThrow(new IllegalStateException("target timeout"));

    executor.executeNode(
        instance,
        node(
            "hook", NodeType.WEBHOOK, "{\"url\":\"https://hooks.example.com\",\"maxAttempts\":2}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(capturedExecution().getResumeAt()).isNotNull();
    verify(port, org.mockito.Mockito.never())
        .transition(any(), eq(WorkflowState.FAILED), anyString());
  }

  @Test
  void webhookFailureFailsWorkflowWhenAttemptsExhausted() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenThrow(new IllegalStateException("target timeout"));

    executor.executeNode(
        instance,
        node(
            "hook", NodeType.WEBHOOK, "{\"url\":\"https://hooks.example.com\",\"maxAttempts\":1}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  @Test
  void subProcessNodeWaitsAndStartsChildInstance() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(
        instance,
        node(
            "sub",
            NodeType.SUB_PROCESS,
            "{\"workflowDefinitionId\":\"00000000-0000-0000-0000-000000000001\"}"));

    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    verify(port).startSubProcess(eq(instance), any(), any());
  }

  @Test
  void subProcessInvalidConfiguredVersionFailsNodeWithoutStartingChild() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();

    executor.executeNode(
        instance,
        node(
            "sub",
            NodeType.SUB_PROCESS,
            "{\"workflowDefinitionId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"version\":0}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(capturedExecution().getError()).contains("positive integer");
    verify(port, never()).startSubProcess(any(), any(), any());
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
  }

  @Test
  void deletedChildDefinitionFailsParentSubProcessNode() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    doThrow(
            new AwapException(
                ErrorCode.WORKFLOW_VERSION_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "Workflow version 1 was not found."))
        .when(port)
        .startSubProcess(any(), any(), any());

    executor.executeNode(
        instance,
        node(
            "sub",
            NodeType.SUB_PROCESS,
            "{\"workflowDefinitionId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"version\":1}"));

    assertThat(capturedExecution().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_FAILED");
    verify(port, never()).advanceFrom(any(), anyString());
  }

  @Test
  void replaySkipsWebhookInvocationAndReusesRecordedOutput() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeExecution recorded = new NodeExecution(tenantId, instance.getId(), "hook", "WEBHOOK");
    recorded.complete("{\"replayed\":true}");
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.of(recorded));

    executor.executeNode(
        instance, node("hook", NodeType.WEBHOOK, "{\"url\":\"https://hooks.example.com\"}"), true);

    verify(webhookInvoker, org.mockito.Mockito.never()).invoke(any(), any(), any());
    verify(port).advanceFrom(instance, "hook");
  }
}
