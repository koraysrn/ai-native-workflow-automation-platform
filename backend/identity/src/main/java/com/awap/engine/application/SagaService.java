package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.SagaInstance;
import com.awap.engine.domain.SagaStatus;
import com.awap.engine.domain.SagaStep;
import com.awap.engine.domain.SagaStepStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.infrastructure.ExecutionLogRepository;
import com.awap.engine.infrastructure.SagaInstanceRepository;
import com.awap.engine.infrastructure.SagaStepRepository;
import com.awap.engine.interfaces.dto.SagaMapper;
import com.awap.engine.interfaces.dto.SagaResponse;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the saga that protects multi-step external integrations.
 *
 * <p>The workflow engine records a {@link SagaStep} whenever an action with a declared compensation
 * succeeds. If a later node fails, {@link #compensateWorkflowFailure} walks the steps in reverse
 * order, retries each compensation with its own budget and dead-letters an exhausted step. Every
 * transition is persisted and audited, so a crash resumes from the first uncompensated step rather
 * than repeating already-completed rollbacks.
 */
@Service
public class SagaService {

  private static final Logger log = LoggerFactory.getLogger(SagaService.class);

  static final String SAGA_KEY_PREFIX = "workflow:";
  static final int DEFAULT_MAX_ATTEMPTS = 3;
  static final long DEFAULT_RETRY_BACKOFF_MS = 1000L;
  static final int DEFAULT_TIMEOUT_SECONDS = 30;

  private final SagaInstanceRepository sagaRepository;
  private final SagaStepRepository stepRepository;
  private final ExecutionLogRepository executionLogRepository;
  private final OutboxEventPublisher outboxEventPublisher;
  private final WebhookClient webhookClient;
  private final SagaMetrics metrics;
  private final SagaMapper mapper;
  private final ObjectMapper objectMapper;
  private final NodeConfigReader configReader;
  private final Clock clock;

  @Autowired
  public SagaService(
      SagaInstanceRepository sagaRepository,
      SagaStepRepository stepRepository,
      ExecutionLogRepository executionLogRepository,
      OutboxEventPublisher outboxEventPublisher,
      WebhookClient webhookClient,
      SagaMetrics metrics,
      SagaMapper mapper,
      ObjectMapper objectMapper) {
    this(
        sagaRepository,
        stepRepository,
        executionLogRepository,
        outboxEventPublisher,
        webhookClient,
        metrics,
        mapper,
        objectMapper,
        Clock.systemUTC());
  }

  SagaService(
      SagaInstanceRepository sagaRepository,
      SagaStepRepository stepRepository,
      ExecutionLogRepository executionLogRepository,
      OutboxEventPublisher outboxEventPublisher,
      WebhookClient webhookClient,
      SagaMetrics metrics,
      SagaMapper mapper,
      ObjectMapper objectMapper,
      Clock clock) {
    this.sagaRepository = sagaRepository;
    this.stepRepository = stepRepository;
    this.executionLogRepository = executionLogRepository;
    this.outboxEventPublisher = outboxEventPublisher;
    this.webhookClient = webhookClient;
    this.metrics = metrics;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.configReader = new NodeConfigReader(objectMapper);
    this.clock = clock;
  }

  /** Starts (or returns) the saga for a workflow instance and a caller-supplied key. */
  @Transactional
  public SagaResponse startSaga(UUID tenantId, UUID workflowInstanceId, String sagaKey) {
    if (sagaKey == null || sagaKey.isBlank()) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "sagaKey is required.");
    }
    Optional<SagaInstance> existing =
        sagaRepository.findByTenantIdAndSagaKeyAndDeletedFalse(tenantId, sagaKey);
    if (existing.isPresent()) {
      return response(existing.get());
    }
    Optional<SagaInstance> byInstance =
        sagaRepository.findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(
            tenantId, workflowInstanceId);
    if (byInstance.isPresent()) {
      return response(byInstance.get());
    }
    Instant now = clock.instant();
    SagaInstance saga = new SagaInstance(tenantId, workflowInstanceId, sagaKey);
    sagaRepository.save(saga);
    audit(saga, null, "SAGA_STARTED", Map.of("sagaKey", sagaKey, "startedAt", now.toString()));
    return response(saga);
  }

  /** Records a completed WEBHOOK/backend action that declared a compensation block. */
  @Transactional
  public void recordNodeCompleted(
      WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
    if (node.getNodeType() == NodeType.SUB_PROCESS) {
      return;
    }
    Optional<SagaCompensationConfig> config = configReader.readSagaCompensation(node);
    if (config.isEmpty()) {
      return;
    }
    SagaCompensationConfig compensation = config.get();
    SagaInstance saga = ensureSaga(instance);
    recordStepIfAbsent(
        saga,
        node,
        execution,
        node.getNodeType().name(),
        compensation.type(),
        webhookCompensationPayload(compensation),
        compensation.maxAttempts(),
        compensation.retryBackoffMs(),
        compensation.timeoutSeconds());
  }

  /** A SUB_PROCESS child finished successfully; the parent may later need to compensate it. */
  @Transactional
  public void recordSubProcessCompleted(
      WorkflowInstance parent, NodeDefinition node, NodeExecution execution, UUID childInstanceId) {
    if (node == null) {
      return;
    }
    SagaInstance saga = ensureSaga(parent);
    recordStepIfAbsent(
        saga,
        node,
        execution,
        NodeType.SUB_PROCESS.name(),
        NodeType.SUB_PROCESS.name(),
        childCompensationPayload(childInstanceId),
        DEFAULT_MAX_ATTEMPTS,
        DEFAULT_RETRY_BACKOFF_MS,
        DEFAULT_TIMEOUT_SECONDS);
  }

  /** Marks a running saga as completed; no compensation is needed for this workflow. */
  @Transactional
  public void markWorkflowCompleted(WorkflowInstance instance) {
    findSaga(instance.getTenantId(), instance.getId())
        .ifPresent(
            saga -> {
              Instant now = clock.instant();
              boolean changed = saga.getStatus() == SagaStatus.RUNNING;
              saga.markCompleted(now);
              if (changed) {
                sagaRepository.save(saga);
                metrics.recordSagaDuration(durationOf(saga, now), saga.getStatus());
                audit(saga, null, "SAGA_COMPLETED", Map.of("completedAt", now.toString()));
              }
            });
  }

  /** Starts reverse compensation after the workflow failed. Idempotent on repeated calls. */
  @Transactional
  public void compensateWorkflowFailure(WorkflowInstance instance, String reason) {
    findSaga(instance.getTenantId(), instance.getId())
        .filter(saga -> !saga.getStatus().isTerminal())
        .ifPresent(
            saga -> {
              Instant now = clock.instant();
              saga.beginCompensation(reason, now);
              sagaRepository.save(saga);
              audit(saga, null, "SAGA_COMPENSATION_REQUESTED", Map.of("reason", reason));
              runCompensation(saga, now);
            });
  }

  /** Recovery-scheduler entry point; a stale or missing lease makes the call a no-op. */
  @Transactional
  public void resumeCompensation(UUID tenantId, UUID sagaInstanceId, UUID leaseToken) {
    SagaInstance saga =
        sagaRepository
            .findByIdAndTenantIdAndDeletedFalse(sagaInstanceId, tenantId)
            .filter(candidate -> candidate.getStatus() == SagaStatus.COMPENSATING)
            .orElse(null);
    if (saga == null) {
      return;
    }
    if (leaseToken != null && !leaseToken.equals(saga.getLeaseToken())) {
      return;
    }
    Instant now = clock.instant();
    runCompensation(saga, now);
    if (saga.getStatus() == SagaStatus.COMPENSATING) {
      saga.releaseLease(now);
      sagaRepository.save(saga);
    }
  }

  /**
   * Manual intervention: starts compensation, or reopens every dead-lettered step after the
   * operator fixed the external system.
   */
  @Transactional
  public SagaResponse manualCompensate(UUID tenantId, UUID workflowInstanceId) {
    SagaInstance saga = requireSaga(tenantId, workflowInstanceId);
    Instant now = clock.instant();
    if (saga.getStatus() == SagaStatus.COMPENSATED) {
      return response(saga);
    }
    if (saga.getStatus() == SagaStatus.COMPLETED) {
      throw new AwapException(
          ErrorCode.SAGA_STATE_CONFLICT,
          HttpStatus.CONFLICT,
          "Saga " + saga.getId() + " completed successfully; there is nothing to compensate.");
    }
    if (saga.getStatus() == SagaStatus.FAILED) {
      saga.reopenCompensation("Manual compensation retry", now);
      resetDeadLetteredSteps(saga, now);
    } else {
      saga.beginCompensation("Manual compensation", now);
    }
    sagaRepository.save(saga);
    audit(saga, null, "SAGA_MANUAL_COMPENSATION", Map.of("requestedAt", now.toString()));
    runCompensation(saga, now);
    return response(saga);
  }

  /** Manual intervention: retries one dead-lettered step, enforcing reverse order. */
  @Transactional
  public SagaResponse retryStep(UUID tenantId, UUID workflowInstanceId, UUID stepId) {
    SagaInstance saga = requireSaga(tenantId, workflowInstanceId);
    SagaStep step =
        stepRepository
            .findBySagaInstanceIdAndId(saga.getId(), stepId)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.SAGA_NOT_FOUND, HttpStatus.NOT_FOUND, "Saga step not found."));
    if (step.getStatus().isCompensated()) {
      return response(saga);
    }
    assertReverseOrder(saga, step);
    Instant now = clock.instant();
    if (saga.getStatus() == SagaStatus.FAILED) {
      saga.reopenCompensation("Manual step retry", now);
    } else if (saga.getStatus() == SagaStatus.RUNNING) {
      saga.beginCompensation("Manual step retry", now);
    } else if (saga.getStatus() == SagaStatus.COMPLETED
        || saga.getStatus() == SagaStatus.COMPENSATED) {
      throw new AwapException(
          ErrorCode.SAGA_STATE_CONFLICT,
          HttpStatus.CONFLICT,
          "Saga " + saga.getId() + " cannot retry a step from " + saga.getStatus() + ".");
    }
    if (step.getStatus() == SagaStepStatus.DLQ) {
      step.resetForManualRetry(now);
    } else {
      step.beginCompensation(now);
    }
    stepRepository.save(step);
    sagaRepository.save(saga);
    audit(
        saga,
        step,
        "SAGA_STEP_MANUAL_RETRY",
        Map.of("stepId", step.getId().toString(), "requestedAt", now.toString()));
    runCompensation(saga, now);
    return response(saga);
  }

  @Transactional(readOnly = true)
  public SagaResponse get(UUID tenantId, UUID workflowInstanceId) {
    return response(requireSaga(tenantId, workflowInstanceId));
  }

  private SagaInstance ensureSaga(WorkflowInstance instance) {
    Optional<SagaInstance> existing =
        sagaRepository.findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(
            instance.getTenantId(), instance.getId());
    if (existing.isPresent()) {
      return existing.get();
    }
    String sagaKey = SAGA_KEY_PREFIX + instance.getId();
    Optional<SagaInstance> byKey =
        sagaRepository.findByTenantIdAndSagaKeyAndDeletedFalse(instance.getTenantId(), sagaKey);
    if (byKey.isPresent()) {
      return byKey.get();
    }
    SagaInstance saga = new SagaInstance(instance.getTenantId(), instance.getId(), sagaKey);
    sagaRepository.save(saga);
    audit(
        saga,
        null,
        "SAGA_STARTED",
        Map.of("sagaKey", sagaKey, "workflowInstanceId", instance.getId().toString()));
    return saga;
  }

  private SagaStep recordStepIfAbsent(
      SagaInstance saga,
      NodeDefinition node,
      NodeExecution execution,
      String actionType,
      String compensationType,
      String compensationPayload,
      int maxAttempts,
      long retryBackoffMs,
      int timeoutSeconds) {
    String idempotencyKey =
        "saga:" + saga.getId() + ":node:" + node.getNodeId() + ":" + execution.getId();
    Optional<SagaStep> existing =
        stepRepository.findBySagaInstanceIdAndIdempotencyKey(saga.getId(), idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    Instant now = clock.instant();
    int stepOrder = Math.toIntExact(stepRepository.countBySagaInstanceId(saga.getId()) + 1L);
    SagaStep step =
        new SagaStep(
            saga.getTenantId(),
            saga.getId(),
            stepOrder,
            node.getNodeId(),
            node.getNodeType().name(),
            actionType,
            execution.getOutputJson(),
            compensationType,
            compensationPayload,
            idempotencyKey,
            maxAttempts,
            retryBackoffMs,
            timeoutSeconds,
            now);
    stepRepository.save(step);
    metrics.recordStepSucceeded();
    audit(
        saga,
        step,
        "SAGA_STEP_SUCCEEDED",
        Map.of(
            "stepOrder", stepOrder,
            "actionType", actionType,
            "compensationType", compensationType,
            "recordedAt", now.toString()));
    log.debug(
        "Recorded saga step {} (order {}) for instance {}",
        step.getId(),
        stepOrder,
        saga.getWorkflowInstanceId());
    return step;
  }

  private void runCompensation(SagaInstance saga, Instant now) {
    if (saga.getStatus() != SagaStatus.COMPENSATING) {
      return;
    }
    List<SagaStep> steps = stepRepository.findBySagaInstanceIdOrderByStepOrderDesc(saga.getId());
    for (SagaStep step : steps) {
      if (step.getStatus().isCompensated()) {
        continue;
      }
      if (step.getStatus().isDeadLettered()) {
        failSaga(saga, step, "Compensation step was dead-lettered.", now);
        return;
      }
      if (step.getStatus() == SagaStepStatus.SUCCEEDED) {
        step.beginCompensation(now);
        stepRepository.save(step);
      }
      if (!step.isDue(now) && step.getNextAttemptAt() != null) {
        saga.scheduleCompensation(step.getNextAttemptAt(), now);
        sagaRepository.save(saga);
        return;
      }
      if (!compensateStep(saga, step, now)) {
        return;
      }
    }
    saga.markCompensated(now);
    sagaRepository.save(saga);
    metrics.recordSagaDuration(durationOf(saga, now), saga.getStatus());
    audit(saga, null, "SAGA_COMPENSATED", Map.of("compensatedAt", now.toString()));
  }

  private boolean compensateStep(SagaInstance saga, SagaStep step, Instant now) {
    audit(
        saga,
        step,
        "SAGA_COMPENSATION_STARTED",
        Map.of("attempt", step.getAttempts() + 1, "startedAt", now.toString()));
    SagaCompensationResult result;
    try {
      result = executeCompensation(saga, step, now);
    } catch (RuntimeException ex) {
      result = SagaCompensationResult.failure(messageOf(ex));
    }
    if (result.success()) {
      step.markCompensated(now);
      stepRepository.save(step);
      metrics.recordStepCompensated();
      audit(saga, step, "SAGA_COMPENSATION_COMPLETED", Map.of("compensatedAt", now.toString()));
      return true;
    }

    int nextAttempt = step.getAttempts() + 1;
    if (nextAttempt < step.getMaxAttempts()) {
      Instant next = now.plusMillis(Math.max(1L, step.getRetryBackoffMs()));
      step.recordCompensationFailure(result.error(), next, now);
      stepRepository.save(step);
      saga.scheduleCompensation(next, now);
      sagaRepository.save(saga);
      metrics.recordStepFailed();
      metrics.recordRetryScheduled();
      audit(
          saga,
          step,
          "SAGA_COMPENSATION_RETRY_SCHEDULED",
          Map.of(
              "attempt", nextAttempt, "nextAttemptAt", next.toString(), "error", result.error()));
      return false;
    }

    step.deadLetter(result.error(), now);
    stepRepository.save(step);
    metrics.recordStepFailed();
    failSaga(saga, step, result.error(), now);
    return false;
  }

  private SagaCompensationResult executeCompensation(
      SagaInstance saga, SagaStep step, Instant now) {
    String type =
        step.getCompensationType() == null
            ? ""
            : step.getCompensationType().trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "WEBHOOK" -> executeWebhookCompensation(step);
      case "SUB_PROCESS" -> executeSubProcessCompensation(saga, step, now);
      default ->
          SagaCompensationResult.failure(
              "Unsupported compensation type '" + step.getCompensationType() + "'.");
    };
  }

  private SagaCompensationResult executeWebhookCompensation(SagaStep step) {
    try {
      JsonNode payload = objectMapper.readTree(step.getCompensationPayload());
      String url = payload.path("url").asText(null);
      String body = payload.path("payload").asText("{}");
      if (url == null || url.isBlank()) {
        return SagaCompensationResult.failure("Compensation URL is missing.");
      }
      WebhookAttemptResult attempt =
          webhookClient.attempt(
              url,
              body,
              step.getIdempotencyKey(),
              Duration.ofSeconds(Math.max(1, step.getTimeoutSeconds())));
      if (attempt.transportFailure()) {
        return SagaCompensationResult.failure(
            attempt.error().orElse("Webhook compensation transport failure."));
      }
      if (attempt.statusCode() >= 400) {
        return SagaCompensationResult.failure(
            "Compensation target returned status " + attempt.statusCode() + ".");
      }
      return SagaCompensationResult.succeeded();
    } catch (JsonProcessingException ex) {
      return SagaCompensationResult.failure("Compensation payload is not valid JSON.");
    }
  }

  private SagaCompensationResult executeSubProcessCompensation(
      SagaInstance saga, SagaStep step, Instant now) {
    UUID childInstanceId;
    try {
      JsonNode payload = objectMapper.readTree(step.getCompensationPayload());
      String raw = payload.path("childInstanceId").asText(null);
      if (raw == null || raw.isBlank()) {
        return SagaCompensationResult.failure("Child instance id is missing.");
      }
      childInstanceId = UUID.fromString(raw);
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      return SagaCompensationResult.failure("Child instance id is invalid.");
    }
    Optional<SagaInstance> child =
        sagaRepository.findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(
            saga.getTenantId(), childInstanceId);
    if (child.isEmpty()) {
      // The child never recorded a compensable action, so there is nothing to undo.
      return SagaCompensationResult.succeeded();
    }
    if (child.get().getId().equals(saga.getId())) {
      return SagaCompensationResult.failure("A saga cannot compensate itself.");
    }
    if (child.get().getStatus() == SagaStatus.COMPENSATED) {
      return SagaCompensationResult.succeeded();
    }
    if (child.get().getStatus() == SagaStatus.FAILED) {
      return SagaCompensationResult.failure(
          "Child saga " + child.get().getId() + " is already failed.");
    }
    child.get().beginCompensation("Parent saga " + saga.getId() + " is compensating.", now);
    sagaRepository.save(child.get());
    runCompensation(child.get(), now);
    return child.get().getStatus() == SagaStatus.COMPENSATED
        ? SagaCompensationResult.succeeded()
        : SagaCompensationResult.failure(
            "Child saga " + child.get().getId() + " did not finish compensation.");
  }

  private void failSaga(SagaInstance saga, SagaStep step, String error, Instant now) {
    if (saga.getStatus() != SagaStatus.COMPENSATING) {
      return;
    }
    String reason =
        "Compensation for step "
            + step.getId()
            + " (node "
            + step.getNodeId()
            + ") exhausted "
            + step.getMaxAttempts()
            + " attempt(s): "
            + error;
    saga.markFailed(reason, now);
    sagaRepository.save(saga);
    metrics.recordSagaDuration(durationOf(saga, now), saga.getStatus());
    audit(
        saga,
        step,
        "SAGA_COMPENSATION_DLQ",
        Map.of("reason", reason, "attempts", step.getAttempts()));
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("tenantId", saga.getTenantId().toString());
    event.put("sagaId", saga.getId().toString());
    event.put("workflowInstanceId", saga.getWorkflowInstanceId().toString());
    event.put("stepId", step.getId().toString());
    event.put("nodeId", step.getNodeId());
    event.put("attempts", step.getAttempts());
    event.put("error", error);
    outboxEventPublisher.publish(
        saga.getTenantId(), "SAGA", saga.getId(), "SAGA_COMPENSATION_DLQ", event);
    log.error("Saga {} compensation dead-lettered: {}", saga.getId(), reason);
  }

  private void assertReverseOrder(SagaInstance saga, SagaStep step) {
    for (SagaStep candidate :
        stepRepository.findBySagaInstanceIdOrderByStepOrderDesc(saga.getId())) {
      if (candidate.getStatus().isCompensated()) {
        continue;
      }
      if (candidate.getId().equals(step.getId())) {
        return;
      }
      break;
    }
    throw new AwapException(
        ErrorCode.SAGA_STEP_OUT_OF_ORDER,
        HttpStatus.CONFLICT,
        "Step "
            + step.getId()
            + " must be compensated after every later step has been compensated.");
  }

  private void resetDeadLetteredSteps(SagaInstance saga, Instant now) {
    stepRepository.findBySagaInstanceIdOrderByStepOrderDesc(saga.getId()).stream()
        .filter(step -> step.getStatus().isDeadLettered())
        .forEach(
            step -> {
              step.resetForManualRetry(now);
              stepRepository.save(step);
            });
  }

  private Optional<SagaInstance> findSaga(UUID tenantId, UUID workflowInstanceId) {
    return sagaRepository.findByTenantIdAndWorkflowInstanceIdAndDeletedFalse(
        tenantId, workflowInstanceId);
  }

  private SagaInstance requireSaga(UUID tenantId, UUID workflowInstanceId) {
    return findSaga(tenantId, workflowInstanceId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.SAGA_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Saga for workflow instance " + workflowInstanceId + " was not found."));
  }

  private SagaResponse response(SagaInstance saga) {
    return mapper.toResponse(
        saga, stepRepository.findBySagaInstanceIdOrderByStepOrderAsc(saga.getId()));
  }

  private String webhookCompensationPayload(SagaCompensationConfig config) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("url", config.url());
    payload.put("payload", config.payload());
    return toJson(payload);
  }

  private String childCompensationPayload(UUID childInstanceId) {
    return toJson(Map.of("childInstanceId", childInstanceId.toString()));
  }

  private void audit(SagaInstance saga, SagaStep step, String action, Map<String, Object> details) {
    executionLogRepository.save(
        new ExecutionLog(
            saga.getTenantId(),
            saga.getWorkflowInstanceId(),
            step == null ? null : step.getNodeId(),
            step == null ? null : step.getNodeType(),
            action,
            toJson(details)));
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize saga audit payload", ex);
    }
  }

  private Duration durationOf(SagaInstance saga, Instant now) {
    return Duration.between(saga.getCreatedAt(), now);
  }

  private String messageOf(RuntimeException ex) {
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }

  private record SagaCompensationResult(boolean success, String error) {

    static SagaCompensationResult succeeded() {
      return new SagaCompensationResult(true, null);
    }

    static SagaCompensationResult failure(String error) {
      return new SagaCompensationResult(false, error);
    }
  }
}
