package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.StartWorkflowRequest;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            outboxEventPublisher);
  }

  @Test
  void startRejectsUnpublishedDefinition() {
    UUID tenantId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    when(workflowDefinitionService.getPublished(tenantId, definitionId))
        .thenThrow(
            new AwapException(
                ErrorCode.VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST,
                "Workflow definition is not published."));

    assertThatThrownBy(
            () -> service.start(tenantId, new StartWorkflowRequest(definitionId, null, null)))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void pauseTransitionsRunningInstanceToPaused() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    WorkflowInstance instance =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.RUNNING, null, null);
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.of(instance));

    var response = service.pause(tenantId, id);

    assertThat(response.state()).isEqualTo("PAUSED");
    verify(outboxEventPublisher)
        .publish(eq(tenantId), eq("WORKFLOW"), eq(instance.getId()), eq("WORKFLOW_PAUSED"), any());
  }

  @Test
  void pauseRejectsTerminalInstance() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    WorkflowInstance instance =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.COMPLETED, null, null);
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.of(instance));

    assertThatThrownBy(() -> service.pause(tenantId, id)).isInstanceOf(AwapException.class);
  }

  @Test
  void resumeTransitionsPausedInstanceToRunningAndReDrivesCompletedNodes() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    WorkflowInstance instance =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.PAUSED, null, null);
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.of(instance));
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndStatus(
            instance.getId(), NodeExecutionStatus.COMPLETED))
        .thenReturn(List.of());

    var response = service.resume(tenantId, id);

    assertThat(response.state()).isEqualTo("RUNNING");
    verify(nodeExecutionRepository)
        .findByWorkflowInstanceIdAndStatus(instance.getId(), NodeExecutionStatus.COMPLETED);
  }
}
