package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.ReplayDivergenceType;
import com.awap.engine.domain.ReplayRun;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.ReplayRunRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.ReplayReport;
import com.awap.identity.IdentityApplication;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Replays against real JPA repositories (H2) to prove paging, audit persistence and no writes. */
@SpringBootTest(
    classes = IdentityApplication.class,
    properties = {"awap.replay.page-size=4", "awap.replay.max-rows=1000"})
class ReplayServiceITest {

  private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

  @Autowired private ReplayService replayService;
  @Autowired private WorkflowInstanceRepository instanceRepository;
  @Autowired private ExecutionLogRepository executionLogRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private ReplayRunRepository replayRunRepository;

  @MockitoBean private WorkflowEngineService engineService;

  private WorkflowInstance persistedInstance(UUID tenant, WorkflowState state) {
    return instanceRepository.save(
        new WorkflowInstance(tenant, UUID.randomUUID(), 1, state, null, "{}"));
  }

  private void persistedNode(UUID tenant, UUID instanceId, String nodeId, String nodeType) {
    NodeExecution execution = new NodeExecution(tenant, instanceId, nodeId, nodeType);
    execution.start();
    execution.complete("{}");
    nodeExecutionRepository.save(execution);
  }

  private void persistedLog(
      UUID tenant,
      UUID instanceId,
      int sequence,
      String action,
      String nodeId,
      String nodeType,
      String payload) {
    ExecutionLog log = new ExecutionLog(tenant, instanceId, nodeId, nodeType, action, payload);
    org.springframework.test.util.ReflectionTestUtils.setField(
        log, "createdAt", BASE.plusMillis(sequence));
    executionLogRepository.save(log);
  }

  private void happyPathLogs(UUID tenant, UUID instanceId) {
    persistedLog(tenant, instanceId, 1, "INSTANCE_STARTED", null, null, "started");
    persistedLog(tenant, instanceId, 2, "NODE_COMPLETED", "start", "START", null);
    persistedLog(tenant, instanceId, 3, "NODE_WAITING", "form", "FORM", null);
    persistedLog(tenant, instanceId, 4, "NODE_COMPLETED", "form", "FORM", "{\"answer\":\"yes\"}");
    persistedLog(tenant, instanceId, 5, "NODE_WAITING", "approval", "APPROVAL", null);
    persistedLog(
        tenant, instanceId, 6, "NODE_COMPLETED", "approval", "APPROVAL", "{\"approved\":true}");
    persistedLog(tenant, instanceId, 7, "NODE_COMPLETED", "end", "END", null);
    persistedLog(tenant, instanceId, 8, "WORKFLOW_COMPLETED", null, null, "completed");
  }

  @Test
  void dryRunPagesRealRowsAndWritesOnlyTheAuditRow() {
    UUID tenant = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    WorkflowInstance instance = persistedInstance(tenant, WorkflowState.COMPLETED);
    persistedNode(tenant, instance.getId(), "start", "START");
    persistedNode(tenant, instance.getId(), "form", "FORM");
    persistedNode(tenant, instance.getId(), "approval", "APPROVAL");
    persistedNode(tenant, instance.getId(), "end", "END");
    happyPathLogs(tenant, instance.getId());

    long logsBefore = executionLogRepository.count();
    long nodesBefore = nodeExecutionRepository.count();
    long instancesBefore = instanceRepository.count();

    ReplayReport report = replayService.dryRun(tenant, instance.getId(), Optional.of(requester));

    assertThat(report.result()).isEqualTo("MATCHED");
    assertThat(report.logRows()).isEqualTo(8);
    assertThat(report.logPages()).isEqualTo(2);
    assertThat(report.divergences()).isEmpty();
    assertThat(report.projection().nodeStatuses())
        .containsEntry("approval", "COMPLETED")
        .containsEntry("form", "COMPLETED");
    assertThat(executionLogRepository.count()).isEqualTo(logsBefore);
    assertThat(nodeExecutionRepository.count()).isEqualTo(nodesBefore);
    assertThat(instanceRepository.count()).isEqualTo(instancesBefore);
    assertThat(
            instanceRepository
                .findByIdAndTenantIdAndDeletedFalse(instance.getId(), tenant)
                .orElseThrow()
                .getState())
        .isEqualTo(WorkflowState.COMPLETED);
    verifyNoInteractions(engineService);

    List<ReplayRun> runs =
        replayRunRepository.findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
            tenant, instance.getId());
    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).isDryRun()).isTrue();
    assertThat(runs.get(0).getResult()).isEqualTo("MATCHED");
    assertThat(runs.get(0).getLogRows()).isEqualTo(8);
    assertThat(runs.get(0).getRequestedBy()).isEqualTo(requester);
    assertThat(runs.get(0).getStateHash()).hasSize(64);
  }

  @Test
  void missingCompletionInRealRowsIsAuditedAsDivergence() {
    UUID tenant = UUID.randomUUID();
    WorkflowInstance instance = persistedInstance(tenant, WorkflowState.COMPLETED);
    persistedNode(tenant, instance.getId(), "start", "START");
    persistedNode(tenant, instance.getId(), "form", "FORM");
    persistedNode(tenant, instance.getId(), "approval", "APPROVAL");
    persistedNode(tenant, instance.getId(), "end", "END");
    happyPathLogs(tenant, instance.getId());
    // Drop the approval completion row from the real log table.
    executionLogRepository.deleteAll(
        executionLogRepository
            .findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId())
            .stream()
            .filter(log -> "approval".equals(log.getNodeId()))
            .filter(log -> "NODE_COMPLETED".equals(log.getAction()))
            .toList());

    long nodesBefore = nodeExecutionRepository.count();
    ReplayReport report = replayService.dryRun(tenant, instance.getId(), Optional.empty());

    assertThat(report.result()).isEqualTo("DIVERGED");
    assertThat(report.divergences())
        .extracting(divergence -> divergence.type())
        .contains(ReplayDivergenceType.MISSING_NODE_COMPLETION.name());
    assertThat(nodeExecutionRepository.count()).isEqualTo(nodesBefore);
    assertThat(
            replayRunRepository.findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
                tenant, instance.getId()))
        .singleElement()
        .satisfies(
            run -> {
              assertThat(run.isDivergence()).isTrue();
              assertThat(run.getDivergenceCount()).isEqualTo(1);
              assertThat(run.getDivergenceDetail()).contains("MISSING_NODE_COMPLETION@approval");
            });
  }

  @Test
  void replayRunsAreOnlyVisibleToTheirTenant() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    WorkflowInstance instanceA = persistedInstance(tenantA, WorkflowState.COMPLETED);
    WorkflowInstance instanceB = persistedInstance(tenantB, WorkflowState.COMPLETED);
    persistedLog(tenantA, instanceA.getId(), 1, "INSTANCE_STARTED", null, null, "a");
    persistedLog(tenantB, instanceB.getId(), 1, "INSTANCE_STARTED", null, null, "b");

    replayService.dryRun(tenantA, instanceA.getId(), Optional.empty());
    replayService.dryRun(tenantB, instanceB.getId(), Optional.empty());

    assertThat(
            replayRunRepository.findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
                tenantA, instanceA.getId()))
        .hasSize(1);
    assertThat(
            replayRunRepository.findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
                tenantA, instanceB.getId()))
        .isEmpty();
    assertThat(
            replayRunRepository.findByTenantIdAndWorkflowInstanceIdOrderByCreatedAtDesc(
                tenantB, instanceA.getId()))
        .isEmpty();
  }
}
