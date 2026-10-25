package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionContextRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.workflow.application.SpelExpressionService;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransitionResolverTest {

  @Mock NodeExecutionRepository nodeExecutionRepository;
  @Mock WorkflowDefinitionService workflowDefinitionService;
  @Mock ExecutionContextRepository executionContextRepository;
  @Mock SpelExpressionService spelExpressionService;
  @Mock WorkflowEnginePort port;

  private TransitionResolver resolver;
  private final UUID tenantId = UUID.randomUUID();
  private final UUID workflowDefinitionId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    resolver =
        new TransitionResolver(
            nodeExecutionRepository,
            workflowDefinitionService,
            executionContextRepository,
            spelExpressionService,
            new ObjectMapper().findAndRegisterModules(),
            port);
  }

  private WorkflowInstance instance() {
    return new WorkflowInstance(
        tenantId, workflowDefinitionId, 1, WorkflowState.RUNNING, null, "{}");
  }

  private NodeDefinition node(String nodeId, NodeType type) {
    return new NodeDefinition(tenantId, versionId, nodeId, type, nodeId, null, 0);
  }

  @Test
  void advanceFromExecutesMatchingTarget() {
    WorkflowInstance instance = instance();
    NodeDefinition from = node("from", NodeType.START);
    NodeDefinition target = node("target", NodeType.TASK);
    when(executionContextRepository.findByWorkflowInstanceId(any())).thenReturn(Optional.empty());
    when(workflowDefinitionService.nodesOf(workflowDefinitionId, 1))
        .thenReturn(List.of(from, target));
    when(workflowDefinitionService.transitionsOf(workflowDefinitionId, 1))
        .thenReturn(List.of(new TransitionDefinition(tenantId, versionId, "from", "target", null)));

    resolver.advanceFrom(instance, "from");

    verify(port).executeNode(instance, target);
  }

  @Test
  void advanceFromMissingTargetThrowsAwapException() {
    WorkflowInstance instance = instance();
    NodeDefinition from = node("from", NodeType.START);
    when(executionContextRepository.findByWorkflowInstanceId(any())).thenReturn(Optional.empty());
    when(workflowDefinitionService.nodesOf(workflowDefinitionId, 1)).thenReturn(List.of(from));
    when(workflowDefinitionService.transitionsOf(workflowDefinitionId, 1))
        .thenReturn(List.of(new TransitionDefinition(tenantId, versionId, "from", "ghost", null)));

    assertThatThrownBy(() -> resolver.advanceFrom(instance, "from"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void advanceFromRoutesMergeTargetToBranchMergeReporting() {
    WorkflowInstance instance = instance();
    NodeDefinition from = node("from", NodeType.PARALLEL);
    NodeDefinition merge = node("merge", NodeType.MERGE);
    when(executionContextRepository.findByWorkflowInstanceId(any())).thenReturn(Optional.empty());
    when(workflowDefinitionService.nodesOf(workflowDefinitionId, 1))
        .thenReturn(List.of(from, merge));
    when(workflowDefinitionService.transitionsOf(workflowDefinitionId, 1))
        .thenReturn(List.of(new TransitionDefinition(tenantId, versionId, "from", "merge", null)));
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndJoinNodeId(any(), eq("merge")))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.advanceFrom(instance, "from"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void evaluateConditionTreatsBlankAsTrue() {
    TransitionDefinition missing = new TransitionDefinition(tenantId, versionId, "a", "b", null);
    TransitionDefinition blank = new TransitionDefinition(tenantId, versionId, "a", "b", "  ");

    assertThat(resolver.evaluateCondition(missing, Map.of())).isTrue();
    assertThat(resolver.evaluateCondition(blank, Map.of())).isTrue();
  }

  @Test
  void evaluateConditionRequiresBooleanTrue() {
    TransitionDefinition transition =
        new TransitionDefinition(tenantId, versionId, "a", "b", "#x > 0");
    when(spelExpressionService.evaluate("#x > 0", Map.of())).thenReturn("not-a-boolean");

    assertThat(resolver.evaluateCondition(transition, Map.of())).isFalse();
  }

  @Test
  void reportBranchMergeWithoutParallelThrowsAwapException() {
    WorkflowInstance instance = instance();
    NodeDefinition merge = node("merge", NodeType.MERGE);
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndJoinNodeId(any(), eq("merge")))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.reportBranchMerge(instance, merge))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void reportBranchMergeExecutesMergeWhenAllBranchesReported() {
    WorkflowInstance instance = instance();
    NodeDefinition merge = node("merge", NodeType.MERGE);
    NodeExecution parallel = new NodeExecution(tenantId, instance.getId(), "fork", "PARALLEL");
    parallel.openBranches(2);
    parallel.recordBranchMerge();
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndJoinNodeId(any(), eq("merge")))
        .thenReturn(Optional.of(parallel));

    resolver.reportBranchMerge(instance, merge);

    assertThat(parallel.getMergedBranchCount()).isEqualTo(2);
    verify(port).executeNode(instance, merge);
  }
}
