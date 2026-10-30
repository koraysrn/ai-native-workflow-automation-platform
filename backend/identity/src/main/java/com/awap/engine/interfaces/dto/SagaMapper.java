package com.awap.engine.interfaces.dto;

import com.awap.engine.domain.SagaInstance;
import com.awap.engine.domain.SagaStep;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps saga entities to their public representation without exposing internal lease state. */
@Component
public class SagaMapper {

  public SagaResponse toResponse(SagaInstance saga, List<SagaStep> steps) {
    return new SagaResponse(
        saga.getId(),
        saga.getTenantId(),
        saga.getWorkflowInstanceId(),
        saga.getSagaKey(),
        saga.getStatus().name(),
        saga.getFailureReason(),
        saga.getCompensationStartedAt(),
        saga.getCompensationCompletedAt(),
        saga.getCreatedAt(),
        saga.getUpdatedAt(),
        steps.stream().map(this::toStep).toList());
  }

  public SagaStepResponse toStep(SagaStep step) {
    return new SagaStepResponse(
        step.getId(),
        step.getStepOrder(),
        step.getNodeId(),
        step.getNodeType(),
        step.getActionType(),
        step.getCompensationType(),
        step.getStatus().name(),
        step.getAttempts(),
        step.getMaxAttempts(),
        step.getRetryBackoffMs(),
        step.getTimeoutSeconds(),
        step.getNextAttemptAt(),
        step.getLastError(),
        step.getCompensatedAt());
  }
}
