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

  @Test
  void startSagaRejectsBlankKeysAndReturnsTheInstanceSagaForAnotherKey() {
    assertThatThrownBy(() -> service.startSaga(TENANT, UUID.randomUUID(), " "))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

    WorkflowInstance workflow = workflow();
    SagaResponse created = service.startSaga(TENANT, workflow.getId(), "checkout:order-1");
    SagaResponse byInstance = service.startSaga(TENANT, workflow.getId(), "checkout:order-2");

    assertThat(byInstance.id()).isEqualTo(created.id());
    assertThat(sagas).hasSize(1);
  }

  @Test
  void recordNodeCompletedSkipsSubProcessAndNodesWithoutCompensation() {
    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(workflow, subProcessNode("sub"), execution(workflow, "sub"));
    service.recordNodeCompleted(
        workflow,
        new NodeDefinition(
            TENANT,
            UUID.randomUUID(),
            "plain-hook",
            NodeType.WEBHOOK,
            "Plain hook",
            "{\"url\":\"https://crm.example.com/hook\"}",
            0),
        execution(workflow, "plain-hook"));
    service.recordSubProcessCompleted(
        workflow, null, execution(workflow, "sub"), UUID.randomUUID());

    assertThat(sagas).isEmpty();
    assertThat(steps).isEmpty();
  }

  @Test
  void resumeIgnoresMissingSagasStaleLeasesAndFutureRetries() {
    service.resumeCompensation(TENANT, UUID.randomUUID(), null);
    assertThat(sagas).isEmpty();

    WorkflowInstance workflow = workflow();
    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.transportFailure("timeout"));
    service.compensateWorkflowFailure(workflow, "NODE_FAILED");
    SagaResponse response = service.get(TENANT, workflow.getId());
    SagaInstance saga = sagas.get(response.id());
    UUID lease = UUID.randomUUID();
    saga.claim(lease, clock.instant().plusSeconds(60), clock.instant());
    sagaRepository.save(saga);

    service.resumeCompensation(TENANT, saga.getId(), UUID.randomUUID());
    assertThat(sagas.get(saga.getId()).getLeaseToken()).isEqualTo(lease);

    service.resumeCompensation(TENANT, saga.getId(), lease);
    assertThat(service.get(TENANT, workflow.getId()).status()).isEqualTo("COMPENSATING");
    assertThat(sagas.get(saga.getId()).getLeaseToken()).isNull();
  }

  @Test
  void resumeFailsSagaWhenTheFirstStepIsAlreadyDeadLettered() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga =
        sagas.get(service.startSaga(TENANT, workflow.getId(), "raw-" + workflow.getId()).id());
    SagaStep step = rawStep(saga, "WEBHOOK", "{\"url\":\"https://erp.example.com/rollback\"}", 1);
    step.beginCompensation(clock.instant());
    step.deadLetter("exhausted", clock.instant());
    stepRepository.save(step);
    saga.beginCompensation("failure", clock.instant());
    sagaRepository.save(saga);

    service.resumeCompensation(TENANT, saga.getId(), null);

    assertThat(service.get(TENANT, workflow.getId()).status()).isEqualTo("FAILED");
    verify(outboxEventPublisher)
        .publish(eq(TENANT), eq("SAGA"), eq(saga.getId()), eq("SAGA_COMPENSATION_DLQ"), any());
  }

  @Test
  void manualCompensationCoversRunningCompensatedCompletedAndFailedStates() {
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"))
        .thenReturn(WebhookAttemptResult.httpError(500, "boom", null))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    WorkflowInstance running = workflow();
    service.recordNodeCompleted(
        running,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(running, "erp-step"));
    assertThat(service.manualCompensate(TENANT, running.getId()).status()).isEqualTo("COMPENSATED");
    assertThat(service.manualCompensate(TENANT, running.getId()).status()).isEqualTo("COMPENSATED");

    WorkflowInstance completed = workflow();
    service.recordNodeCompleted(
        completed,
        webhookNode("crm-step", "https://crm.example.com/rollback"),
        execution(completed, "crm-step"));
    service.markWorkflowCompleted(completed);
    assertThatThrownBy(() -> service.manualCompensate(TENANT, completed.getId()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_STATE_CONFLICT));

    WorkflowInstance failed = workflow();
    service.recordNodeCompleted(
        failed,
        webhookNode("erp-step", "https://erp.example.com/rollback", 1),
        execution(failed, "erp-step"));
    service.compensateWorkflowFailure(failed, "NODE_FAILED");
    assertThat(service.get(TENANT, failed.getId()).status()).isEqualTo("FAILED");
    assertThat(service.manualCompensate(TENANT, failed.getId()).status()).isEqualTo("COMPENSATED");
  }

  @Test
  void retryStepCoversMissingCompensatedRunningAndCompletedStates() {
    WorkflowInstance workflow = workflow();
    assertThatThrownBy(() -> service.retryStep(TENANT, workflow.getId(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_NOT_FOUND));

    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));
    SagaResponse response = service.get(TENANT, workflow.getId());
    assertThatThrownBy(() -> service.retryStep(TENANT, workflow.getId(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_NOT_FOUND));

    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));
    UUID stepId = response.steps().getFirst().id();
    assertThat(service.retryStep(TENANT, workflow.getId(), stepId).status())
        .isEqualTo("COMPENSATED");
    assertThat(service.retryStep(TENANT, workflow.getId(), stepId).status())
        .isEqualTo("COMPENSATED");
    verify(webhookClient, times(1)).attempt(anyString(), anyString(), anyString(), any());

    WorkflowInstance completed = workflow();
    service.recordNodeCompleted(
        completed,
        webhookNode("crm-step", "https://crm.example.com/rollback"),
        execution(completed, "crm-step"));
    service.markWorkflowCompleted(completed);
    SagaResponse completedSaga = service.get(TENANT, completed.getId());
    assertThatThrownBy(
            () ->
                service.retryStep(TENANT, completed.getId(), completedSaga.steps().getFirst().id()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SAGA_STATE_CONFLICT));
  }

  @Test
  void unsupportedCompensationTypeIsDeadLettered() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga =
        sagas.get(service.startSaga(TENANT, workflow.getId(), "raw-" + workflow.getId()).id());
    rawStep(saga, "UNKNOWN", "{}", 1);

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    SagaResponse failed = service.get(TENANT, workflow.getId());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.failureReason()).contains("Unsupported compensation type");
  }

  @Test
  void webhookCompensationRejectsMissingUrlAndMalformedPayload() {
    WorkflowInstance missingUrl = workflow();
    SagaInstance missingUrlSaga =
        sagas.get(service.startSaga(TENANT, missingUrl.getId(), "raw-" + missingUrl.getId()).id());
    rawStep(missingUrlSaga, "WEBHOOK", "{}", 1);
    service.compensateWorkflowFailure(missingUrl, "NODE_FAILED");
    assertThat(service.get(TENANT, missingUrl.getId()).failureReason())
        .contains("Compensation URL is missing");

    WorkflowInstance malformed = workflow();
    SagaInstance malformedSaga =
        sagas.get(service.startSaga(TENANT, malformed.getId(), "raw-" + malformed.getId()).id());
    rawStep(malformedSaga, "WEBHOOK", "not-json", 1);
    service.compensateWorkflowFailure(malformed, "NODE_FAILED");
    assertThat(service.get(TENANT, malformed.getId()).failureReason()).contains("not valid JSON");
  }

  @Test
  void webhookCompensationUsesTheDefaultTransportMessage() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga =
        sagas.get(service.startSaga(TENANT, workflow.getId(), "raw-" + workflow.getId()).id());
    rawStep(saga, "WEBHOOK", "{\"url\":\"https://erp.example.com/rollback\"}", 1);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(new WebhookAttemptResult(0, "", Optional.empty(), Optional.empty()));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    assertThat(service.get(TENANT, workflow.getId()).failureReason()).contains("transport failure");
  }

  @Test
  void subProcessCompensationHandlesMissingInvalidSelfAndFailedChildren() {
    WorkflowInstance missing = workflow();
    SagaInstance missingSaga =
        sagas.get(service.startSaga(TENANT, missing.getId(), "raw-" + missing.getId()).id());
    rawStep(missingSaga, "SUB_PROCESS", "{\"childInstanceId\":\"" + UUID.randomUUID() + "\"}", 1);
    service.compensateWorkflowFailure(missing, "NODE_FAILED");
    assertThat(service.get(TENANT, missing.getId()).status()).isEqualTo("COMPENSATED");

    WorkflowInstance invalid = workflow();
    SagaInstance invalidSaga =
        sagas.get(service.startSaga(TENANT, invalid.getId(), "raw-" + invalid.getId()).id());
    rawStep(invalidSaga, "SUB_PROCESS", "{\"childInstanceId\":\"not-a-uuid\"}", 1);
    service.compensateWorkflowFailure(invalid, "NODE_FAILED");
    assertThat(service.get(TENANT, invalid.getId()).failureReason())
        .contains("Child instance id is invalid");

    WorkflowInstance self = workflow();
    SagaInstance selfSaga =
        sagas.get(service.startSaga(TENANT, self.getId(), "raw-" + self.getId()).id());
    rawStep(selfSaga, "SUB_PROCESS", "{\"childInstanceId\":\"" + self.getId() + "\"}", 1);
    service.compensateWorkflowFailure(self, "NODE_FAILED");
    assertThat(service.get(TENANT, self.getId()).failureReason())
        .contains("cannot compensate itself");

    WorkflowInstance child = workflow();
    SagaInstance childSaga =
        sagas.get(service.startSaga(TENANT, child.getId(), "raw-" + child.getId()).id());
    childSaga.beginCompensation("child failed", clock.instant());
    childSaga.markFailed("child failed", clock.instant());
    sagaRepository.save(childSaga);
    WorkflowInstance parent = workflow();
    SagaInstance parentSaga =
        sagas.get(service.startSaga(TENANT, parent.getId(), "raw-" + parent.getId()).id());
    rawStep(parentSaga, "SUB_PROCESS", "{\"childInstanceId\":\"" + child.getId() + "\"}", 1);
    service.compensateWorkflowFailure(parent, "NODE_FAILED");
    assertThat(service.get(TENANT, parent.getId()).failureReason()).contains("already failed");
  }

  @Test
  void recordNodeCompletedFindsTheSagaByKeyWhenInstanceLookupMisses() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga = new SagaInstance(TENANT, UUID.randomUUID(), "workflow:" + workflow.getId());
    sagaRepository.save(saga);

    service.recordNodeCompleted(
        workflow,
        webhookNode("erp-step", "https://erp.example.com/rollback"),
        execution(workflow, "erp-step"));

    assertThat(steps.values())
        .singleElement()
        .satisfies(step -> assertThat(step.getSagaInstanceId()).isEqualTo(saga.getId()));
  }

  @Test
  void webhookCompensationRuntimeExceptionIsContained() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga =
        sagas.get(service.startSaga(TENANT, workflow.getId(), "raw-" + workflow.getId()).id());
    rawStep(saga, "WEBHOOK", "{\"url\":\"https://erp.example.com/rollback\"}", 1);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("SSRF blocked"));

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    assertThat(service.get(TENANT, workflow.getId()).failureReason()).contains("SSRF blocked");
  }

  @Test
  void nullCompensationTypeIsDeadLettered() {
    WorkflowInstance workflow = workflow();
    SagaInstance saga =
        sagas.get(service.startSaga(TENANT, workflow.getId(), "raw-" + workflow.getId()).id());
    rawStep(saga, null, "{}", 1);

    service.compensateWorkflowFailure(workflow, "NODE_FAILED");

    assertThat(service.get(TENANT, workflow.getId()).failureReason())
        .contains("Unsupported compensation type");
  }

  @Test
  void subProcessCompensationCoversMissingIdCompensatedChildAndUnfinishedChild() {
    WorkflowInstance missingId = workflow();
    SagaInstance missingIdSaga =
        sagas.get(service.startSaga(TENANT, missingId.getId(), "raw-" + missingId.getId()).id());
    rawStep(missingIdSaga, "SUB_PROCESS", "{}", 1);
    service.compensateWorkflowFailure(missingId, "NODE_FAILED");
    assertThat(service.get(TENANT, missingId.getId()).failureReason())
        .contains("Child instance id is missing");

    WorkflowInstance compensatedChild = workflow();
    SagaInstance compensatedChildSaga =
        sagas.get(
            service
                .startSaga(TENANT, compensatedChild.getId(), "raw-" + compensatedChild.getId())
                .id());
    compensatedChildSaga.beginCompensation("child compensated", clock.instant());
    compensatedChildSaga.markCompensated(clock.instant());
    sagaRepository.save(compensatedChildSaga);
    WorkflowInstance compensatedParent = workflow();
    SagaInstance compensatedParentSaga =
        sagas.get(
            service
                .startSaga(TENANT, compensatedParent.getId(), "raw-" + compensatedParent.getId())
                .id());
    rawStep(
        compensatedParentSaga,
        "SUB_PROCESS",
        "{\"childInstanceId\":\"" + compensatedChild.getId() + "\"}",
        1);
    service.compensateWorkflowFailure(compensatedParent, "NODE_FAILED");
    assertThat(service.get(TENANT, compensatedParent.getId()).status()).isEqualTo("COMPENSATED");

    WorkflowInstance unfinishedChild = workflow();
    SagaInstance unfinishedChildSaga =
        sagas.get(
            service
                .startSaga(TENANT, unfinishedChild.getId(), "raw-" + unfinishedChild.getId())
                .id());
    rawStep(unfinishedChildSaga, "WEBHOOK", "{\"url\":\"https://erp.example.com/rollback\"}", 1);
    WorkflowInstance unfinishedParent = workflow();
    SagaInstance unfinishedParentSaga =
        sagas.get(
            service
                .startSaga(TENANT, unfinishedParent.getId(), "raw-" + unfinishedParent.getId())
                .id());
    rawStep(
        unfinishedParentSaga,
        "SUB_PROCESS",
        "{\"childInstanceId\":\"" + unfinishedChild.getId() + "\"}",
        1);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.httpError(500, "boom", null));
    service.compensateWorkflowFailure(unfinishedParent, "NODE_FAILED");
    assertThat(service.get(TENANT, unfinishedParent.getId()).failureReason())
        .contains("did not finish compensation");
  }

  @Test
  void retryStepSkipsAlreadyCompensatedLaterSteps() {
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
    saga.beginCompensation("failure", clock.instant());
    sagaRepository.save(saga);
    SagaStep second = steps.get(running.steps().get(1).id());
    second.markCompensated(clock.instant());
    stepRepository.save(second);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenReturn(WebhookAttemptResult.success(200, "{}"));

    SagaResponse retried =
        service.retryStep(TENANT, workflow.getId(), running.steps().getFirst().id());

    assertThat(retried.status()).isEqualTo("COMPENSATED");
    assertThat(retried.steps())
        .extracting(SagaStepResponse::status)
        .containsExactly("COMPENSATED", "COMPENSATED");
  }

  @Test
  void nullAndBlankInputsUseTheDefensiveBranches() {
    assertThatThrownBy(() -> service.startSaga(TENANT, UUID.randomUUID(), null))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

    WorkflowInstance blankUrl = workflow();
    SagaInstance blankUrlSaga =
        sagas.get(service.startSaga(TENANT, blankUrl.getId(), "raw-" + blankUrl.getId()).id());
    rawStep(blankUrlSaga, "WEBHOOK", "{\"url\":\"  \"}", 1);
    service.compensateWorkflowFailure(blankUrl, "NODE_FAILED");
    assertThat(service.get(TENANT, blankUrl.getId()).failureReason())
        .contains("Compensation URL is missing");

    WorkflowInstance blankChild = workflow();
    SagaInstance blankChildSaga =
        sagas.get(service.startSaga(TENANT, blankChild.getId(), "raw-" + blankChild.getId()).id());
    rawStep(blankChildSaga, "SUB_PROCESS", "{\"childInstanceId\":\"  \"}", 1);
    service.compensateWorkflowFailure(blankChild, "NODE_FAILED");
    assertThat(service.get(TENANT, blankChild.getId()).failureReason())
        .contains("Child instance id is missing");

    WorkflowInstance noMessage = workflow();
    SagaInstance noMessageSaga =
        sagas.get(service.startSaga(TENANT, noMessage.getId(), "raw-" + noMessage.getId()).id());
    rawStep(noMessageSaga, "WEBHOOK", "{\"url\":\"https://erp.example.com/rollback\"}", 1);
    when(webhookClient.attempt(anyString(), anyString(), anyString(), any()))
        .thenThrow(new RuntimeException());

    service.compensateWorkflowFailure(noMessage, "NODE_FAILED");

    assertThat(service.get(TENANT, noMessage.getId()).failureReason()).contains("RuntimeException");
  }

  private SagaStep rawStep(
      SagaInstance saga, String compensationType, String compensationPayload, int maxAttempts) {
    int order = Math.toIntExact(stepRepository.countBySagaInstanceId(saga.getId()) + 1L);
    SagaStep step =
        new SagaStep(
            TENANT,
            saga.getId(),
            order,
            "raw-" + order,
            "WEBHOOK",
            "WEBHOOK",
            "{}",
            compensationType,
            compensationPayload,
            "raw:" + UUID.randomUUID(),
            maxAttempts,
            1L,
            5,
            clock.instant());
    return stepRepository.save(step);
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
