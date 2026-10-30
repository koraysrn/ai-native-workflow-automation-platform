package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.SagaInstance;
import com.awap.engine.domain.SagaStep;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.SagaInstanceRepository;
import com.awap.engine.infrastructure.SagaStepRepository;
import com.awap.engine.interfaces.dto.SagaMapper;
import com.awap.engine.interfaces.dto.SagaResponse;
import com.awap.engine.interfaces.dto.SagaStepResponse;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Orchestration, reverse ordering, retry, DLQ and intervention semantics of the saga service. */
@ExtendWith(MockitoExtension.class)
class SagaServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DEFINITION_ID = UUID.randomUUID();

  @Mock private SagaInstanceRepository sagaRepository;
  @Mock private SagaStepRepository stepRepository;
  @Mock private ExecutionLogRepository executionLogRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private WebhookClient webhookClient;

  private final Map<UUID, SagaInstance> sagas = new LinkedHashMap<>();
  private final Map<UUID, SagaStep> steps = new LinkedHashMap<>();
  private final List<ExecutionLog> auditLogs = new ArrayList<>();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private SagaService service;

  @BeforeEach
  void setUp() {
    lenient()
        .when(sagaRepository.save(any(SagaInstance.class)))
        .thenAnswer(
            invocation -> {
              SagaInstance saga = invocation.getArgument(0);
              sagas.put(saga.getId(), saga);
              return saga;
            });
    lenient()
        .when(stepRepository.save(any(SagaStep.class)))
        .thenAnswer(
            invocation -> {
              SagaStep step = invocation.getArgument(0);
              steps.put(step.getId(), step);
              return step;
            });
    lenient()
        .when(executionLogRepository.save(any(ExecutionLog.class)))
        .thenAnswer(
            invocation -> {
              ExecutionLog log = invocation.getArgument(0);
              auditLogs.add(log);
              return log;
            });
    lenient()
        .when(sagaRepository.findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(any(), any()))
        .thenAnswer(invocation -> findSaga(invocation.getArgument(0), invocation.getArgument(1)));
    lenient()
        .when(sagaRepository.findByTenantIdAndSagaKeyAndDeletedFalse(any(), anyString()))
        .thenAnswer(
            invocation ->
                sagas.values().stream()
                    .filter(saga -> saga.getTenantId().equals(invocation.getArgument(0)))
                    .filter(saga -> saga.getSagaKey().equals(invocation.getArgument(1)))
                    .findFirst());
    lenient()
        .when(sagaRepository.findByIdAndTenantIdAndDeletedFalse(any(), any()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(sagas.get(invocation.<UUID>getArgument(0)))
                    .filter(saga -> saga.getTenantId().equals(invocation.getArgument(1))));
    lenient()
        .when(stepRepository.findBySagaInstanceIdOrderByStepOrderDesc(any()))
        .thenAnswer(invocation -> orderedSteps(invocation.getArgument(0), true));
    lenient()
        .when(stepRepository.findBySagaInstanceIdOrderByStepOrderAsc(any()))
        .thenAnswer(invocation -> orderedSteps(invocation.getArgument(0), false));
    lenient()
        .when(stepRepository.findBySagaInstanceIdAndIdempotencyKey(any(), anyString()))
        .thenAnswer(
            invocation ->
                steps.values().stream()
                    .filter(step -> step.getSagaInstanceId().equals(invocation.getArgument(0)))
                    .filter(step -> step.getIdempotencyKey().equals(invocation.getArgument(1)))
                    .findFirst());
    lenient()
        .when(stepRepository.findBySagaInstanceIdAndId(any(), any()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(steps.get(invocation.<UUID>getArgument(1)))
                    .filter(step -> step.getSagaInstanceId().equals(invocation.getArgument(0))));
    lenient()
        .when(stepRepository.countBySagaInstanceId(any()))
        .thenAnswer(
            invocation ->
                steps.values().stream()
                    .filter(step -> step.getSagaInstanceId().equals(invocation.getArgument(0)))
                    .count());

    service =
        new SagaService(
            sagaRepository,
            stepRepository,
            executionLogRepository,
            outboxEventPublisher,
            webhookClient,
            new SagaMetrics(meterRegistry),
            new SagaMapper(),
            new ObjectMapper(),
            clock);
  }

  @Test
  void successfulTwoStepSagaRecordsBothStepsAndCompletes() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    service.recordNodeCompleted(
        workflow,
        webhookNode("crm-step", "https://crm.example.com/rollback"),
        execution(workflow, "crm-step"));

    service.markWorkflowCompleted(workflow);

    SagaResponse saga = service.get(TENANT, workflow.getId());
    assertThat(saga.status()).isEqualTo("COMPLETED");
    assertThat(saga.steps())
        .extracting(SagaStepResponse::nodeId)
        .containsExactly("erp-step", "crm-step");
    assertThat(saga.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("SUCCEEDED"));
    assertThat(counter("succeeded")).isEqualTo(2.0);
    verify(webhookClient, never()).attempt(anyString(), anyString(), anyString(), any());
  }

  @Test
  void failureOnStepTwoCompensatesStepOneAndMarksSagaCompensated() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    SagaResponse saga = service.get(TENANT, workflow.getId());
    assertThat(saga.status()).isEqualTo("COMPENSATED");
    assertThat(saga.steps())
        .singleElement()
        .satisfies(
            step -> {
              assertThat(step.status()).isEqualTo("COMPENSATED");
              assertThat(step.compensatedAt()).isNotNull();
            });
    assertThat(counter("compensated")).isEqualTo(1.0);
    assertThat(auditLogs)
        .extracting(ExecutionLog::getAction)
        .contains(
            "SAGA_STEP_SUCCEEDED", "SAGA_COMPENSATION_STARTED", "SAGA_COMPENSATION_COMPLETED");
  }

  @Test
  void duplicateCompensationCallIsANoOp() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");
    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    assertThat(service.get(TENANT, workflow.getId()).status()).isEqualTo("COMPENSATED");
    verify(webhookClient, times(1)).attempt(anyString(), anyString(), anyString(), any());
  }

  @Test
  void crashAfterAStepWasCompensatedResumesAtTheFirstUncompensatedStep() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("first", "https://erp.example.com/rollback"),
        execution(workflow, "first"));
    service.recordNodeCompleted(
        workflow,
        webhookNode("second", "https://crm.example.com/rollback"),
        execution(workflow, "second"));
    SagaResponse running = service.get(TENANT, workflow.getId());
    SagaInstance saga = sagas.get(running.id());
    saga.beginCompensation("simulated crash", clock.instant());
    sagaRepository.save(saga);
    SagaStep second = steps.get(running.steps().get(1).id());
    second.markCompensated(clock.instant());
    stepRepository.save(second);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    service.resumeCompensation(TENANT, saga.getId(), null);

    SagaResponse resumed = service.get(TENANT, workflow.getId());
    assertThat(resumed.status()).isEqualTo("COMPENSATED");
    assertThat(resumed.steps())
        .extracting(SagaStepResponse::status)
        .containsExactly("COMPENSATED", "COMPENSATED");
    verify(webhookClient, times(1))
        .attempt(eq("https://erp.example.com/rollback"), anyString(), anyString(), any());
  }

  @Test
  void compensationRetryReusesTheSameIdempotencyKey() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.transportFailure("timeout"))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");
    SagaResponse afterFailure = service.get(TENANT, workflow.getId());
    assertThat(afterFailure.status()).isEqualTo("COMPENSATING");
    assertThat(afterFailure.steps().getFirst().attempts()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(2));
    service.resumeCompensation(TENANT, UUID.fromString(afterFailure.id().toString()), null);

    SagaResponse afterRetry = service.get(TENANT, workflow.getId());
    assertThat(afterRetry.status()).isEqualTo("COMPENSATED");
    assertThat(afterRetry.steps().getFirst().status()).isEqualTo("COMPENSATED");
    assertThat(afterRetry.steps().getFirst().attempts()).isEqualTo(1);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(webhookClient, times(2)).attempt(anyString(), anyString(), keys.capture(), any());
    assertThat(keys.getAllValues()).containsOnly(keys.getAllValues().getFirst());
    assertThat(counter("compensated")).isEqualTo(1.0);
  }

  @Test
  void duplicateStepRecordingIsANoOp() {
    WorkflowInstance workflow = workflow();
    NodeDefinition node = webhookNode("erp-step", "https://erp.example.com/rollback");
    NodeExecution execution = execution(workflow, "erp-step");

    service.recordNodeCompleted(workflow, node, execution);
    service.recordNodeCompleted(workflow, node, execution);

    assertThat(service.get(TENANT, workflow.getId()).steps()).hasSize(1);
  }

  @Test
  void exhaustedCompensationDeadLettersTheStepAndFailsTheSaga() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback", 2),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.httpError(500, "boom", null));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");
    SagaResponse afterFirstAttempt = service.get(TENANT, workflow.getId());
    assertThat(afterFirstAttempt.status()).isEqualTo("COMPENSATING");

    clock.advance(Duration.ofSeconds(2));
    service.resumeCompensation(TENANT, UUID.fromString(afterFirstAttempt.id().toString()), null);

    SagaResponse saga = service.get(TENANT, workflow.getId());
    assertThat(saga.status()).isEqualTo("FAILED");
    assertThat(saga.failureReason()).contains("exhausted");
    assertThat(saga.steps().getFirst().status()).isEqualTo("DLQ");
    assertThat(saga.steps().getFirst().attempts()).isEqualTo(2);
    assertThat(counter("failed")).isEqualTo(2.0);
    verify(outboxEventPublisher)
        .publish(eq(TENANT), eq("SAGA"), any(), eq("SAGA_COMPENSATION_DLQ"), any());
  }

  @Test
  void manualRetryResumesAFailedCompensation() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback", 1),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.httpError(500, "boom", null));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");
    SagaResponse failed = service.get(TENANT, workflow.getId());
    assertThat(failed.status()).isEqualTo("FAILED");
    UUID stepId = failed.steps().getFirst().id();

    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));
    SagaResponse retried = service.retryStep(TENANT, workflow.getId(), stepId);

    assertThat(retried.status()).isEqualTo("COMPENSATED");
    assertThat(retried.steps().getFirst().status()).isEqualTo("COMPENSATED");
  }

  @Test
  void outOfOrderStepRetryIsRejected() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("first", "https://erp.example.com/rollback"),
        execution(workflow, "first"));
    service.recordNodeCompleted(
        workflow,
        webhookNode("second", "https://crm.example.com/rollback"),
        execution(workflow, "second"));
    SagaResponse saga = service.get(TENANT, workflow.getId());

    assertThatThrownBy(
            () -> service.retryStep(TENANT, workflow.getId(), saga.steps().getFirst().id()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_STEP_OUT_OF_ORDER);
              assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  void duplicateSagaStartReturnsTheExistingSaga() {
    WorkflowInstance workflow = workflow();

    SagaResponse first = service.startSaga(TENANT, workflow.getId(), "checkout:order-1");
    SagaResponse second = service.startSaga(TENANT, workflow.getId(), "checkout:order-1");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(sagas).hasSize(1);
  }

  @Test
  void crossTenantSagaAccessIsBlocked() {
    WorkflowInstance workflow = workflow();
    service.startSaga(TENANT, workflow.getId(), "checkout:order-1");

    assertThatThrownBy(() -> service.get(UUID.randomUUID(), workflow.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_NOT_FOUND);
              assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
  }

  @Test
  void subProcessCompensationCompensatesTheChildSaga() {
    WorkflowInstance parent = workflow();
    WorkflowInstance child = workflow();
    service.recordNodeCompleted(
        child,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(child, "erp-step"));
    service.markWorkflowCompleted(child);
    service.recordSubProcessCompleted(
        parent, subProcessNode("child-call"), execution(parent, "child-call"), child.getId());
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    service.compensateWorkflowFailure(parent, "SUB_PROCESS_FAILED");

    assertThat(service.get(TENANT, parent.getId()).status()).isEqualTo("COMPENSATED");
    assertThat(service.get(TENANT, child.getId()).status()).isEqualTo("COMPENSATED");
    verify(webhookClient)
        .attempt(eq("https://erp.example.com/rollback"), anyString(), anyString(), any());
  }

  private Optional<SagaInstance> findSaga(UUID tenantId, UUID workflowInstanceId) {
    return sagas.values().stream()
        .filter(saga -> saga.getTenantId().equals(tenantId))
        .filter(saga -> saga.getWorkflowInstanceId().equals(workflowInstanceId))
        .findFirst();
  }

  private List<SagaStep> orderedSteps(UUID sagaId, boolean descending) {
    Comparator<SagaStep> order = Comparator.comparingInt(SagaStep::getStepOrder);
    if (descending) {
      order = order.reversed();
    }
    return steps.values().stream()
        .filter(step -> step.getSagaInstanceId().equals(sagaId))
        .sorted(order)
        .toList();
  }

  private WorkflowInstance workflow() {
    return new WorkflowInstance(TENANT, DEFINITION_ID, 1, WorkflowState.RUNNING, null, "{}");
  }

  private NodeExecution execution(WorkflowInstance workflow, String nodeId) {
    NodeExecution execution = new NodeExecution(TENANT, workflow.getId(), nodeId, "WEBHOOK");
    execution.start();
    execution.complete("{\"ok\":true}");
    return execution;
  }

  private NodeDefinition webhookNode(String nodeId, String compensationUrl) {
    return webhookNode(nodeId, compensationUrl, 3);
  }

  private NodeDefinition webhookNode(String nodeId, String compensationUrl, int maxAttempts) {
    String config =
        "{\"url\":\"https://crm.example.com/hook\",\"compensation\":{"
            + "\"type\":\"WEBHOOK\",\"url\":\""
            + compensationUrl
            + "\",\"payload\":\"{\\\"id\\\":1}\",\"maxAttempts\":"
            + maxAttempts
            + ",\"retryBackoffMs\":1000,\"timeoutSeconds\":5}}";
    return new NodeDefinition(
        TENANT, UUID.randomUUID(), nodeId, NodeType.WEBHOOK, nodeId, config, 0);
  }

  private NodeDefinition subProcessNode(String nodeId) {
    return new NodeDefinition(
        TENANT, UUID.randomUUID(), nodeId, NodeType.SUB_PROCESS, nodeId, "{}", 0);
  }

  private double counter(String outcome) {
    return meterRegistry.get(SagaMetrics.STEPS).tag("outcome", outcome).counter().count();
  }

  /** Mutable clock so retry backoff and recovery can be exercised without sleeping. */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
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
      return instant;
    }
  }
}
