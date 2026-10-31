package com.awap.workflow.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer instruments for designer expression validation.
 *
 * <p>{@code awap.workflow.expression.validations} is a counter tagged with {@code outcome} ({@code
 * valid} or {@code invalid}). It measures how often users submit an invalid condition, which is the
 * signal that the builder, autocomplete or inline guidance needs attention.
 */
@Component
public class WorkflowExpressionValidationMetrics {

  public static final String VALIDATIONS = "awap.workflow.expression.validations";

  public static final String OUTCOME_VALID = "valid";
  public static final String OUTCOME_INVALID = "invalid";

  private final MeterRegistry meterRegistry;

  public WorkflowExpressionValidationMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void record(boolean valid) {
    meterRegistry
        .counter(VALIDATIONS, "outcome", valid ? OUTCOME_VALID : OUTCOME_INVALID)
        .increment();
  }
}
