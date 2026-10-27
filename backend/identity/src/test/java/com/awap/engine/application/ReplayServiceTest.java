package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.ReplayLogEntry;
import com.awap.engine.domain.ReplayTestFixtures;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.engine.interfaces.dto.ReplayReport;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayServiceTest {

  private static final UUID TENANT = ReplayTestFixtures.TENANT;
  private static final UUID INSTANCE = ReplayTestFixtures.INSTANCE;
  private static final UUID REQUESTER = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Mock private WorkflowInstanceRepository instanceRepository;
  @Mock private ExecutionLogRepository executionLogRepository;
  @Mock private NodeExecutionRepository nodeExecutionRepository;
  @Mock private WorkflowEngineService engineService;
  @Mock private ReplayAuditWriter auditWriter;

  private SimpleMeterRegistry meterRegistry;
  private ReplayMetrics metrics;
  private ReplayLockRegistry lockRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new ReplayMetrics(meterRegistry);
    lockRegistry = new ReplayLockRegistry();
  }

  private ReplayService service(ReplaySettings settings, Clock clock) {
    return new ReplayService(
        instanceRepository,
        executionLogRepository,
        nodeExecutionRepository,
        engineService,
        auditWriter,
        new JsonReplayPayloadSummarizer(new ObjectMapper()),
        settings,
        metrics,
        lockRegistry,
        clock);
  }

  private ReplayService service() {
    return service(ReplaySettings.defaults(), Clock.fixed(ReplayTestFixtures.BASE, ZoneOffset.UTC));
  }

  private WorkflowInstance instance(WorkflowState state, long version) {
    WorkflowInstance instance =
        new WorkflowInstance(TENANT, UUID.randomUUID(), 1, state, null, "{}");
    ReflectionTestUtils.setField(instance, "id", INSTANCE);
    ReflectionTestUtils.setField(instance, "version", version);
    return instance;
  }

  private void found(WorkflowInstance instance) {
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(INSTANCE, TENANT))
        .thenReturn(Optional.of(instance));
  }

  private void logs(List<ReplayLogEntry> entries) {
    List<ExecutionLog> persisted = entries.stream().map(this::persisted).toList();
    when(executionLogRepository.findReplayPage(eq(TENANT), eq(INSTANCE), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              Pageable pageable = invocation.getArgument(2);
              return new PageImpl<>(persisted, pageable, persisted.size());
            });
  }

  private ExecutionLog persisted(ReplayLogEntry entry) {
    ExecutionLog log =
        new ExecutionLog(
            entry.tenantId(),
            entry.workflowInstanceId(),
            entry.nodeId().orElse(null),
            entry.nodeType().orElse(null),
            entry.action(),
            entry.payload().orElse(null));
    ReflectionTestUtils.setField(log, "id", entry.id());
    ReflectionTestUtils.setField(log, "createdAt", entry.createdAt());
    return log;
  }

  private NodeExecution nodeExecution(String nodeId, String nodeType) {
    NodeExecution execution = new NodeExecution(TENANT, INSTANCE, nodeId, nodeType);
    execution.start();
    execution.complete("{}");
    return execution;
  }

  private void persistedNodes(NodeExecution... executions) {
    when(nodeExecutionRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(INSTANCE))
        .thenReturn(List.of(executions));
  }

  private void happyPathNodes() {
    persistedNodes(
        nodeExecution("start", "START"),
        nodeExecution("form", "FORM"),
        nodeExecution("approval", "APPROVAL"),
        nodeExecution("end", "END"));
  }

  @Test
  void dryRunProjectsMatchedStateAndWritesOnlyTheAuditRow() {
    found(instance(WorkflowState.COMPLETED, 1L));
    logs(ReplayTestFixtures.happyPath());
    happyPathNodes();

    ReplayReport report = service().dryRun(TENANT, INSTANCE, Optional.of(REQUESTER));

    assertThat(report.result()).isEqualTo("MATCHED");
    assertThat(report.dryRun()).isTrue();
    assertThat(report.errorCode()).isEmpty();
    assertThat(report.logRows()).isEqualTo(8);
    assertThat(report.projection().instanceState()).isEqualTo("COMPLETED");
    assertThat(report.projection().nodeStatuses())
        .containsEntry("approval", "COMPLETED")
        .containsEntry("form", "COMPLETED");
    assertThat(report.divergences()).isEmpty();
    assertThat(report.stateHash()).hasSize(64);

    verify(auditWriter)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                run ->
                    run.isDryRun()
                        && "MATCHED".equals(run.getResult())
                        && !run.isDivergence()
                        && run.getLogRows() == 8
                        && run.getRequestedBy().equals(REQUESTER)));
    verifyNoInteractions(engineService);
    verify(nodeExecutionRepository, never()).save(any(NodeExecution.class));
    verify(instanceRepository, never()).save(any(WorkflowInstance.class));
    verify(executionLogRepository, never()).save(any(ExecutionLog.class));
  }

  @Test
  void replayServiceHasNoSideEffectCollaborators() {
    List<String> collaboratorTypes =
        Arrays.stream(ReplayService.class.getDeclaredFields())
            .map(Field::getType)
            .map(Class::getSimpleName)
            .toList();

    assertThat(collaboratorTypes)
        .doesNotContain("WebhookInvoker", "OutboxEventPublisher", "TaskService", "NodeExecutor");
  }

  @Test
  void replayIsRejectedWhenProjectionDiverges() {
    found(instance(WorkflowState.COMPLETED, 1L));
    // Drop every approval log row while the node row stays COMPLETED: the log is incomplete.
    logs(
        ReplayTestFixtures.happyPath().stream()
            .filter(entry -> !"approval".equals(entry.nodeId().orElse("")))
            .toList());
    happyPathNodes();

    assertThatThrownBy(() -> service().replay(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_DIVERGENCE);
              assertThat(awap.status().value()).isEqualTo(409);
            });

    verifyNoInteractions(engineService);
    verify(auditWriter)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                run ->
                    !run.isDryRun() && "DIVERGED".equals(run.getResult()) && run.isDivergence()));
    assertThat(
            meterRegistry
                .get("awap.replay.divergences")
                .tag("type", "MISSING_NODE_COMPLETION")
                .counter()
                .count())
        .isPositive();
    assertThat(lockRegistry.isHeld(INSTANCE)).isFalse();
  }

  @Test
  void replayDelegatesToTheReferenceEngineOnlyAfterMatching() {
    found(instance(WorkflowState.COMPLETED, 1L));
    logs(ReplayTestFixtures.happyPath());
    happyPathNodes();
    WorkflowInstanceResponse response =
        new WorkflowInstanceResponse(
            INSTANCE, TENANT, UUID.randomUUID(), 1, "RUNNING", "{}", null, Instant.now());
    when(engineService.replay(TENANT, INSTANCE)).thenReturn(response);

    WorkflowInstanceResponse result = service().replay(TENANT, INSTANCE, Optional.empty());

    assertThat(result).isSameAs(response);
    verify(engineService).replay(TENANT, INSTANCE);
    verify(auditWriter)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                run -> !run.isDryRun() && "MATCHED".equals(run.getResult())));
  }

  @Test
  void concurrentReplayReturns409WithoutTouchingTheInstance() {
    lockRegistry.tryAcquire(INSTANCE);

    assertThatThrownBy(() -> service().replay(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_CONFLICT);
              assertThat(awap.status().value()).isEqualTo(409);
            });

    verifyNoInteractions(instanceRepository, executionLogRepository, engineService, auditWriter);
    assertThat(
            meterRegistry
                .get("awap.replay.limits")
                .tag("reason", "concurrent-replay")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void crossTenantReplayReturns404() {
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(INSTANCE, TENANT))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().dryRun(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
              assertThat(awap.status().value()).isEqualTo(404);
            });

    verifyNoInteractions(executionLogRepository, auditWriter);
  }

  @Test
  void rowCapIsAControlledError() {
    found(instance(WorkflowState.COMPLETED, 1L));
    logs(ReplayTestFixtures.happyPath());
    ReplaySettings settings = new ReplaySettings(2, 500, Duration.ofSeconds(30));

    assertThatThrownBy(
            () ->
                service(settings, Clock.fixed(ReplayTestFixtures.BASE, ZoneOffset.UTC))
                    .dryRun(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_LOG_TOO_LARGE);
              assertThat(awap.status().value()).isEqualTo(413);
            });

    verify(auditWriter, never()).record(any());
  }

  @Test
  void timeoutIsAControlledError() {
    found(instance(WorkflowState.COMPLETED, 1L));
    logs(ReplayTestFixtures.happyPath());
    ReplaySettings settings = new ReplaySettings(100_000, 500, Duration.ofSeconds(1));

    assertThatThrownBy(
            () ->
                service(settings, new AdvancingClock(Duration.ofSeconds(2)))
                    .dryRun(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_TIMEOUT);
              assertThat(awap.status().value()).isEqualTo(422);
            });

    verify(auditWriter, never()).record(any());
  }

  @Test
  void logOutageDuringDryRunWritesNoPartialState() {
    found(instance(WorkflowState.COMPLETED, 1L));
    when(executionLogRepository.findReplayPage(eq(TENANT), eq(INSTANCE), any(Pageable.class)))
        .thenThrow(new DataAccessResourceFailureException("database is down"));

    assertThatThrownBy(() -> service().dryRun(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_LOG_UNAVAILABLE);
              assertThat(awap.status().value()).isEqualTo(503);
            });

    verify(auditWriter, never()).record(any());
    verifyNoInteractions(engineService);
    verify(nodeExecutionRepository, never()).save(any());
    verify(instanceRepository, never()).save(any());
  }

  @Test
  void concurrentInstanceModificationFailsTheOptimisticVersionCheck() {
    WorkflowInstance firstRead = instance(WorkflowState.COMPLETED, 1L);
    WorkflowInstance secondRead = instance(WorkflowState.COMPLETED, 2L);
    when(instanceRepository.findByIdAndTenantIdAndDeletedFalse(INSTANCE, TENANT))
        .thenReturn(Optional.of(firstRead), Optional.of(secondRead));
    logs(ReplayTestFixtures.happyPath());
    happyPathNodes();

    assertThatThrownBy(() -> service().replay(TENANT, INSTANCE, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.REPLAY_CONFLICT);
              assertThat(awap.status().value()).isEqualTo(409);
            });

    verify(engineService, never()).replay(eq(TENANT), eq(INSTANCE));
  }

  @Test
  void pagesAreStreamedAndCounted() {
    found(instance(WorkflowState.COMPLETED, 1L));
    List<ReplayLogEntry> entries = ReplayTestFixtures.happyPath();
    List<ExecutionLog> firstPage = new ArrayList<>();
    List<ExecutionLog> secondPage = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      (i < 4 ? firstPage : secondPage).add(persisted(entries.get(i)));
    }
    when(executionLogRepository.findReplayPage(eq(TENANT), eq(INSTANCE), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              Pageable pageable = invocation.getArgument(2);
              Page<ExecutionLog> page =
                  pageable.getPageNumber() == 0
                      ? new PageImpl<>(firstPage, pageable, entries.size())
                      : new PageImpl<>(secondPage, pageable, entries.size());
              return page;
            });
    happyPathNodes();
    ReplaySettings settings = new ReplaySettings(100_000, 4, Duration.ofSeconds(30));

    ReplayReport report =
        service(settings, Clock.fixed(ReplayTestFixtures.BASE, ZoneOffset.UTC))
            .dryRun(TENANT, INSTANCE, Optional.empty());

    assertThat(report.logRows()).isEqualTo(8);
    assertThat(report.logPages()).isEqualTo(2);
    assertThat(report.result()).isEqualTo("MATCHED");
  }

  @Test
  void settingsRejectNonPositiveValues() {
    assertThatThrownBy(() -> new ReplaySettings(0, 500, Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReplaySettings(100, 0, Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReplaySettings(100, 10, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ReplaySettings.defaults().maxRows()).isEqualTo(100_000);
  }

  @Test
  void userRepositoryIsNotPartOfTheReplayPath() {
    // Guards against a future refactor that starts driving nodes through the engine during replay.
    assertThat(Arrays.stream(ReplayService.class.getDeclaredFields()).map(Field::getType))
        .doesNotContain(UserRepository.class);
  }

  /** Clock that advances on every read so timeout handling is deterministic in tests. */
  private static final class AdvancingClock extends Clock {

    private final Duration step;
    private Instant current = ReplayTestFixtures.BASE;

    private AdvancingClock(Duration step) {
      this.step = step;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      current = current.plus(step);
      return current;
    }
  }
}
