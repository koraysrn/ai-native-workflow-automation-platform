package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the consistency guarantee of a database write that hits a statement timeout: the aborted
 * work is rolled back completely, and the timeout outcome (execution {@code TIMED_OUT}, instance
 * {@code FAILED}, {@code NODE_TIMED_OUT} audit row) is then persisted through the real engine path.
 */
@SpringBootTest(classes = com.awap.identity.IdentityApplication.class)
class NodeTimeoutRollbackITest {

  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private WorkflowInstanceRepository workflowInstanceRepository;
  @Autowired private ExecutionLogRepository executionLogRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  private NodeTimeoutHandler handler(WorkflowEnginePort port) {
    return new NodeTimeoutHandler(
        nodeExecutionRepository,
        new NodeConfigReader(objectMapper),
        port,
        new NodeTimeoutMetrics(new SimpleMeterRegistry()),
        objectMapper);
  }

  /** Minimal port that writes through the real repositories instead of mocking persistence. */
  private WorkflowEnginePort persistencePort() {
    return new WorkflowEnginePort() {
      @Override
      public void transition(WorkflowInstance instance, WorkflowState target, String action) {
        instance.transitionTo(target);
        workflowInstanceRepository.save(instance);
        executionLogRepository.save(
            new ExecutionLog(
                instance.getTenantId(),
                instance.getId(),
                null,
                null,
                action,
                "State advanced to " + target));
      }

      @Override
      public void log(
          WorkflowInstance instance,
          String nodeId,
          String nodeType,
          String action,
          String payload) {
        executionLogRepository.save(
            new ExecutionLog(
                instance.getTenantId(), instance.getId(), nodeId, nodeType, action, payload));
      }

      @Override
      public void executeNode(WorkflowInstance instance, NodeDefinition node) {
        throw new UnsupportedOperationException("not used by this test");
      }

      @Override
      public void advanceFrom(WorkflowInstance instance, String fromNodeId) {
        throw new UnsupportedOperationException("not used by this test");
      }

      @Override
      public void startSubProcess(
          WorkflowInstance parent, NodeDefinition node, NodeExecution execution) {
        throw new UnsupportedOperationException("not used by this test");
      }

      @Override
      public void recordSagaStep(
          WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
        throw new UnsupportedOperationException("not used by this test");
      }
    };
  }

  @Test
  void databaseWriteTimeoutRollsBackWorkAndLeavesTheExecutionTimedOut() {
    UUID tenantId = UUID.randomUUID();
    WorkflowInstance instance =
        new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
    workflowInstanceRepository.save(instance);
    NodeExecution execution = new NodeExecution(tenantId, instance.getId(), "hook", "WEBHOOK");
    nodeExecutionRepository.save(execution);

    TransactionTemplate template = new TransactionTemplate(transactionManager);

    // The node's database write runs, then the statement times out: the whole unit of work must
    // roll back so no half-applied state survives.
    assertThatThrownBy(
            () ->
                template.executeWithoutResult(
                    status -> {
                      NodeExecution managed =
                          nodeExecutionRepository.findById(execution.getId()).orElseThrow();
                      managed.start();
                      nodeExecutionRepository.save(managed);
                      executionLogRepository.save(
                          new ExecutionLog(
                              tenantId,
                              instance.getId(),
                              "hook",
                              "WEBHOOK",
                              "NODE_PROGRESS",
                              "partial write before the statement timeout"));
                      throw new QueryTimeoutException(
                          "canceling statement due to statement timeout");
                    }))
        .isInstanceOf(QueryTimeoutException.class);

    NodeExecution afterRollback = nodeExecutionRepository.findById(execution.getId()).orElseThrow();
    assertThat(afterRollback.getStatus()).isEqualTo(NodeExecutionStatus.PENDING);
    assertThat(afterRollback.getAttempt()).isZero();
    assertThat(executionLogRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId()))
        .isEmpty();

    // The engine then records the timeout outcome in a fresh transaction.
    NodeTimeoutHandler handler = handler(persistencePort());
    NodeDefinition node =
        new NodeDefinition(
            tenantId,
            instance.getWorkflowDefinitionId(),
            "hook",
            NodeType.WEBHOOK,
            "Hook",
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":1,\"maxAttempts\":1}",
            0);
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1));
    template.executeWithoutResult(
        status -> {
          NodeExecution managed = nodeExecutionRepository.findById(execution.getId()).orElseThrow();
          WorkflowInstance managedInstance =
              workflowInstanceRepository.findById(instance.getId()).orElseThrow();
          managed.start();
          handler.handle(
              managedInstance,
              node,
              managed,
              resolution,
              Duration.ofMillis(1200),
              "Node 'hook' exceeded its 1000ms timeout after 1200ms.",
              false);
        });

    NodeExecution persisted = nodeExecutionRepository.findById(execution.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(persisted.getError()).contains("exceeded its 1000ms timeout");
    assertThat(workflowInstanceRepository.findById(instance.getId()).orElseThrow().getState())
        .isEqualTo(WorkflowState.FAILED);

    List<ExecutionLog> logs =
        executionLogRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId());
    List<ExecutionLog> timeoutLogs =
        logs.stream().filter(log -> "NODE_TIMED_OUT".equals(log.getAction())).toList();
    assertThat(timeoutLogs).hasSize(2);
    ExecutionLog nodeAudit =
        timeoutLogs.stream().filter(log -> log.getNodeId() != null).findFirst().orElseThrow();
    assertThat(nodeAudit.getNodeType()).isEqualTo("WEBHOOK");
    assertThat(nodeAudit.getPayload()).contains("\"enforcement\":\"ENFORCED\"");
    assertThat(nodeAudit.getPayload()).contains("\"retryScheduled\":false");
  }
}
