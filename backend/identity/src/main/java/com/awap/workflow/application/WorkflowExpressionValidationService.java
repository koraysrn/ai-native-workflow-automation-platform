package com.awap.workflow.application;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Dry-run validation of a designer expression without persisting a definition.
 *
 * <p>This is the shared contract behind the edge inspector: the frontend mirrors the fast checks
 * for instant feedback, while this service remains the authoritative parser and denylist. A blank
 * expression is valid because it denotes an unconditional transition.
 */
@Service
public class WorkflowExpressionValidationService {

  /** JSON field the designer binds the inline error to. */
  public static final String EXPRESSION_FIELD = "expression";

  private final SpelExpressionService spelExpressionService;
  private final WorkflowExpressionValidationMetrics metrics;

  public WorkflowExpressionValidationService(
      SpelExpressionService spelExpressionService, WorkflowExpressionValidationMetrics metrics) {
    this.spelExpressionService = spelExpressionService;
    this.metrics = metrics;
  }

  public ExpressionValidationResult validate(String expression) {
    List<String> problems = spelExpressionService.problems(expression);
    boolean valid = problems.isEmpty();
    metrics.record(valid);
    if (valid) {
      return ExpressionValidationResult.validResult();
    }
    return ExpressionValidationResult.invalid(EXPRESSION_FIELD, problems.getFirst());
  }
}
