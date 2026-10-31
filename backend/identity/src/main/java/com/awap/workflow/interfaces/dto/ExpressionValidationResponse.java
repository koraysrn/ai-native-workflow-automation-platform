package com.awap.workflow.interfaces.dto;

import java.util.List;

/** Result of a dry-run expression validation, with field-level errors for inline rendering. */
public record ExpressionValidationResponse(boolean valid, List<FieldError> errors) {

  /** A single field-level validation problem. */
  public record FieldError(String field, String message) {}
}
