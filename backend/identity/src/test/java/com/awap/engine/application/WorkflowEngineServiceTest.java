package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.EngineMapperImpl;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class WorkflowEngineServiceTest {

  @Mock private WorkflowInstanceRepository instanceRepository;
  @Mock private ExecutionLogRepository executionLogRepository;
  @Mock private WorkflowDefinitionService workflowDefinitionService;
  @Mock private NodeExecutionRepository nodeExecutionRepository;
  @Mock private ExecutionContextRepository executionContextRepository;
  @Mock private TaskService taskService;
  @Mock private SpelExpressionService spelExpressionService;
  @Mock private UserRepository userRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;

  private WorkflowEngineService service;
  private final UUID tenantId = UUID.randomUUID();
  private final UUID workflowDefinitionId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new WorkflowEngineService(
            instanceRepository,
            executionLogRepository,
            workflowDefinitionService,
            nodeExecutionRepository,
            executionContextRepository,
            taskService,
            spelExpressionService,
            new ObjectMapper(),
            userRepository,
            outboxEventPublisher,
            new EngineMapperImpl());
  }

  private WorkflowInstance instance(WorkflowState state) {
    return new WorkflowInstance(tenantId, workflowDefinitionId, 1, state, null, "{}");
  }

  private void stubFoundInstance(WorkflowInstance instance) {
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(any(), eq(tenantId)))
        .thenReturn(Optional.of(instance));
  }

  @Test
  void startRejectsUnpublishedDefinition() {
    UUID definitionId = UUID.randomUUID();
    when(workflowDefinitionService.getPublished(tenantId, definitionId))
        .thenThrow(
            new AwapException(
                ErrorCode.VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST,
                "Workflow definition is not published."));

    assertThatThrownBy(
            () -> service.start(tenantId, new StartWorkflowRequest(definitionId, null, "{}")))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void startCreatesInstanceAndExecutesStartNode() {
    WorkflowDefinition definition = new WorkflowDefinition(tenantId, "Approval flow", null);
    definition.publish(1);
    when(workflowDefinitionService.getPublished(tenantId, workflowDefinitionId))
        .thenReturn(definition);
    when(instanceRepository.save(any(WorkflowInstance.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(executionContextRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(workflowDefinitionService.nodesOf(definition.getId(), 1))
        .thenReturn(
            List.of(
                new NodeDefinition(
                    tenantId, UUID.randomUUID(), "start", NodeType.START, "start", null, 0)));
    when(workflowDefinitionService.transitionsOf(definition.getId(), 1)).thenReturn(List.of());
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.empty());
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    WorkflowInstanceResponse response =
        service.start(tenantId, new StartWorkflowRequest(workflowDefinitionId, null, "{}"));

    assertThat(response.state()).isEqualTo("RUNNING");
  }

  @Test
  void completeTransitionsToCompleted() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFoundInstance(instance);

    WorkflowInstanceResponse response = service.complete(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("COMPLETED");
  }

  @Test
  void failTransitionsToFailed() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFoundInstance(instance);

    WorkflowInstanceResponse response = service.fail(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("FAILED");
  }

  @Test
  void cancelTransitionsToCancelled() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFoundInstance(instance);

    WorkflowInstanceResponse response = service.cancel(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("CANCELLED");
  }

  @Test
  void retryTransitionsFailedInstanceToRunning() {
    WorkflowInstance instance = instance(WorkflowState.FAILED);
    stubFoundInstance(instance);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndStatus(
            any(), eq(NodeExecutionStatus.FAILED)))
        .thenReturn(List.of());

    WorkflowInstanceResponse response = service.retry(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("RUNNING");
  }

  @Test
  void replayTransitionsFailedInstanceToRunning() {
    WorkflowInstance instance = instance(WorkflowState.FAILED);
    stubFoundInstance(instance);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndStatus(
            any(), eq(NodeExecutionStatus.FAILED)))
        .thenReturn(List.of());

    WorkflowInstanceResponse response = service.replay(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("RUNNING");
  }

  @Test
  void pauseTransitionsRunningInstanceToPaused() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFoundInstance(instance);

    WorkflowInstanceResponse response = service.pause(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("PAUSED");
    verify(outboxEventPublisher)
        .publish(eq(tenantId), eq("WORKFLOW"), eq(instance.getId()), eq("WORKFLOW_PAUSED"), any());
  }

  @Test
  void pauseRejectsTerminalInstance() {
    WorkflowInstance instance = instance(WorkflowState.COMPLETED);
    stubFoundInstance(instance);

    assertThatThrownBy(() -> service.pause(tenantId, instance.getId()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void resumeTransitionsPausedInstanceToRunningAndReDrivesCompletedNodes() {
    WorkflowInstance instance = instance(WorkflowState.PAUSED);
    stubFoundInstance(instance);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndStatus(
            instance.getId(), NodeExecutionStatus.COMPLETED))
        .thenReturn(List.of());

    WorkflowInstanceResponse response = service.resume(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("RUNNING");
    verify(nodeExecutionRepository)
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.COMPLETED);
  }

  @Test
  void resumeDueNodeCompletesWaitingNode() {
    WorkflowInstance instance = instance(WorkflowState.WAITING);
    NodeExecution execution = new NodeExecution(tenantId, instance.getId(), "delay", "DELAY");
    execution.waitForExternal();
    execution.scheduleResume(Instant.now().plusSeconds(10));
    stubFoundInstance(instance);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(instance.getId(), "delay"))
        .thenReturn(Optional.of(execution));
    when(workflowDefinitionService.nodesOf(any(), anyInt())).thenReturn(List.of());
    when(workflowDefinitionService.transitionsOf(any(), anyInt())).thenReturn(List.of());
    when(executionContextRepository.findByWorkflowInstanceId(any())).thenReturn(Optional.empty());

    service.resumeDueNode(tenantId, instance.getId(), "delay");

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(execution.getResumeAt()).isNull();
  }

  @Test
  void completeNodeCompletesRunningExecutionAndAdvances() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    NodeExecution execution = new NodeExecution(tenantId, instance.getId(), "form", "FORM");
    execution.start();
    stubFoundInstance(instance);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(instance.getId(), "form"))
        .thenReturn(Optional.of(execution));
    when(workflowDefinitionService.nodesOf(any(), anyInt())).thenReturn(List.of());
    when(workflowDefinitionService.transitionsOf(any(), anyInt())).thenReturn(List.of());
    when(executionContextRepository.findByWorkflowInstanceId(any())).thenReturn(Optional.empty());

    WorkflowInstanceResponse response =
        service.completeNode(tenantId, instance.getId(), "form", "{}");

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(response.state()).isEqualTo("RUNNING");
  }

  @Test
  void listMapsInstances() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    when(instanceRepository.findByTenantIdAndDeletedFalseOrderByCreatedAtDesc(tenantId))
        .thenReturn(List.of(instance));

    List<WorkflowInstanceResponse> responses = service.list(tenantId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).state()).isEqualTo("RUNNING");
  }

  @Test
  void getMapsInstance() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFoundInstance(instance);

    WorkflowInstanceResponse response = service.get(tenantId, instance.getId());

    assertThat(response.state()).isEqualTo("RUNNING");
  }

  @Test
  void startUsesIdempotencyKeyWhenProvided() {
    WorkflowInstance existing = instance(WorkflowState.RUNNING);
    when(instanceRepository.findByTenantIdAndIdempotencyKey(tenantId, "key-1"))
        .thenReturn(Optional.of(existing));

    WorkflowInstanceResponse response =
        service.start(tenantId, new StartWorkflowRequest(workflowDefinitionId, "key-1", "{}"));

    assertThat(response.state()).isEqualTo("RUNNING");
    verify(instanceRepository).findByTenantIdAndIdempotencyKey(tenantId, "key-1");
  }
}
