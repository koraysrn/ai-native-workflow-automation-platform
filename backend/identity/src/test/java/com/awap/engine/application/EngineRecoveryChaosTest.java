package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Crash/restart recovery of the engine state machine (issue #211). */
@Tag("chaos")
class EngineRecoveryChaosTest {

  private final NodeExecutionRepository executions = mock(NodeExecutionRepository.class);
  private final WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
  private final TaskService tasks = mock(TaskService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final WorkflowEnginePort port = mock(WorkflowEnginePort.class);
  private final WebhookInvoker webhooks = mock(WebhookInvoker.class);

  private final UUID tenantId = UUID.randomUUID();
  private final UUID definitionId = UUID.randomUUID();
  private WorkflowInstance instance;
  private NodeExecutor executor;

  @BeforeEach
  void setUp() {
    instance = new WorkflowInstance(tenantId, definitionId, 1, WorkflowState.RUNNING, null, "{}");
    executor =
        new NodeExecutor(
            executions,
            definitions,
            new NodeConfigReader(new ObjectMapper().findAndRegisterModules()),
            tasks,
            users,
            port,
            webhooks);
    when(executions.save(any(NodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private NodeDefinition node(String id, NodeType type) {
    return new NodeDefinition(tenantId, UUID.randomUUID(), id, type, id, "{}", 0);
  }

  @Test
  void aCrashMidNodeRecoversFromTheLastCompletedNode() {
    NodeDefinition start = node("start", NodeType.START);
    NodeDefinition task = node("task", NodeType.TASK);
    NodeExecution completed = new NodeExecution(tenantId, instance.getId(), "start", "START");
    completed.start();
    completed.complete("{}");
    NodeExecution running = new NodeExecution(tenantId, instance.getId(), "task", "TASK");
    running.start();

    when(executions.findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId()))
        .thenReturn(List.of(completed, running));
    when(executions.findByWorkflowInstanceIdAndNodeId(instance.getId(), "task"))
        .thenReturn(Optional.of(running));
    when(definitions.nodesOf(definitionId, 1)).thenReturn(List.of(start, task));

    executor.replayFromRecordedState(instance);

    assertThat(completed.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(completed.getAttempt()).isEqualTo(1);
    assertThat(running.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(running.getAttempt()).isEqualTo(2);
    verify(tasks, times(1)).create(eq(tenantId), any());
    verify(executions, never()).save(completed);
  }

  @Test
  void aReplayNeverRepeatsAnExternalCall() {
    NodeExecution webhookExecution =
        new NodeExecution(tenantId, instance.getId(), "hook", "WEBHOOK");
    webhookExecution.start();
    when(executions.findByWorkflowInstanceIdAndNodeId(instance.getId(), "hook"))
        .thenReturn(Optional.of(webhookExecution));
    NodeDefinition hook = node("hook", NodeType.WEBHOOK);

    executor.executeNode(instance, hook, true);

    assertThat(webhookExecution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(webhookExecution.getError()).contains("not repeated during replay");
    verifyNoInteractions(webhooks);
    verify(port)
        .log(eq(instance), eq("hook"), eq("WEBHOOK"), eq("NODE_REPLAY_SKIPPED"), anyString());
  }

  @Test
  void aRecoveryTimerCannotAdvanceAPausedWorkflow() {
    instance = new WorkflowInstance(tenantId, definitionId, 1, WorkflowState.PAUSED, null, "{}");
    NodeDefinition task = node("task", NodeType.TASK);

    executor.executeNode(instance, task);

    verifyNoInteractions(executions, tasks, webhooks);
    assertThat(instance.getState()).isEqualTo(WorkflowState.PAUSED);
  }
}
