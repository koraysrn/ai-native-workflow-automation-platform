package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dry-run validation contract shared with the designer edge inspector. */
class WorkflowExpressionValidationServiceTest {

  private SimpleMeterRegistry meterRegistry;
  private WorkflowExpressionValidationService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service =
        new WorkflowExpressionValidationService(
            new SpelExpressionService(), new WorkflowExpressionValidationMetrics(meterRegistry));
  }

  @Test
  void acceptsAValidExpressionAndCountsIt() {
    ExpressionValidationResult result = service.validate("#amount > 100");

    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(counter("valid")).isEqualTo(1.0);
  }

  @Test
  void treatsBlankAsUnconditionalAndValid() {
    assertThat(service.validate("").valid()).isTrue();
    assertThat(service.validate("   ").valid()).isTrue();
    assertThat(counter("valid")).isEqualTo(2.0);
  }

  @Test
  void returnsAFieldLevelErrorForAMalformedExpression() {
    ExpressionValidationResult result = service.validate("1 +");

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .containsExactly(
            new ExpressionValidationResult.FieldError(
                WorkflowExpressionValidationService.EXPRESSION_FIELD, "Expression is invalid."));
    assertThat(counter("invalid")).isEqualTo(1.0);
  }

  @Test
  void returnsAFieldLevelErrorForAForbiddenExpression() {
    ExpressionValidationResult result = service.validate("T(java.lang.Runtime).getRuntime()");

    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().getFirst().field()).isEqualTo("expression");
    assertThat(result.errors().getFirst().message()).contains("forbidden pattern");
  }

  @Test
  void returnsAClearMessageForAnOversizedExpression() {
    ExpressionValidationResult result = service.validate("#x > " + "1".repeat(5000));

    assertThat(result.valid()).isFalse();
    assertThat(result.errors().getFirst().message()).contains("maximum length");
  }

  private double counter(String outcome) {
    return meterRegistry
        .get(WorkflowExpressionValidationMetrics.VALIDATIONS)
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
