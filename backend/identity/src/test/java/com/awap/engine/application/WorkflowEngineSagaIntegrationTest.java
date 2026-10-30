package com.awap.engine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.EngineMapperImpl;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.usage.application.UsageMeteringService;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Proves the engine drives the saga on completion, failure and node completion callbacks. */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineSagaIntegrationTest {

  @Mock private WorkflowInstanceRepository instanceRepository;
  @Mock private ExecutionLogRepository executionLogRepository;
  @Mock private WorkflowDefinitionService workflowDefinitionService;
  @Mock private NodeExecutionRepository nodeExecutionRepository;
  @Mock private ExecutionContextRepository executionContextRepository;
  @Mock private TaskService taskService;
  @Mock private SpelExpressionService spelExpressionService;
  @Mock private UserRepository userRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private UsageMeteringService usageMeteringService;
  @Mock private SagaService sagaService;

  private WorkflowEngineService service;
  private final UUID tenantId = UUID.randomUUID();

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
            new EngineMapperImpl(),
            usageMeteringService,
            sagaService);
  }

  @Test
  void failingTheWorkflowStartsSagaCompensation() {
    WorkflowInstance instance = workflow(WorkflowState.RUNNING);

    service.transition(instance, WorkflowState.FAILED, "NODE_FAILED");

    verify(sagaService).compensateWorkflowFailure(instance, "NODE_FAILED");
  }

  @Test
  void completingTheWorkflowMarksTheSagaCompleted() {
    WorkflowInstance instance = workflow(WorkflowState.RUNNING);

    service.transition(instance, WorkflowState.COMPLETED, "WORKFLOW_COMPLETED");

    verify(sagaService).markWorkflowCompleted(instance);
  }

  @Test
  void nodeCompletionCallbackRecordsASagaStep() {
    WorkflowInstance instance = workflow(WorkflowState.RUNNING);
    NodeExecution execution = new NodeExecution(tenantId, instance.getId(), "hook", "WEBHOOK");
    NodeDefinition node =
        new NodeDefinition(
            tenantId,
            UUID.randomUUID(),
            "hook",
            NodeType.WEBHOOK,
            "Hook",
            "{\"url\":\"https://crm.example.com/hook\"}",
            0);

    service.recordSagaStep(instance, node, execution);

    verify(sagaService).recordNodeCompleted(eq(instance), eq(node), any(NodeExecution.class));
  }

  private WorkflowInstance workflow(WorkflowState state) {
    return new WorkflowInstance(tenantId, UUID.randomUUID(), 1, state, null, "{}");
  }
}
