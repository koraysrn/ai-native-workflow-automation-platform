package com.awap.workflow.application;

import java.util.List;

/**
 * Outcome of a dry-run expression validation.
 *
 * <p>The designer renders one inline error per entry, keyed by the JSON field so the same contract
 * can be reused when a condition is attached to an edge or a node.
 */
public record ExpressionValidationResult(boolean valid, List<FieldError> errors) {

  /** A single field-level validation problem. */
  public record FieldError(String field, String message) {}

  public static ExpressionValidationResult validResult() {
    return new ExpressionValidationResult(true, List.of());
  }

  public static ExpressionValidationResult invalid(String field, String message) {
    return new ExpressionValidationResult(false, List.of(new FieldError(field, message)));
  }
}
