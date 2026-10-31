package com.awap.workflow.interfaces.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Dry-run request for the designer expression validator.
 *
 * <p>{@code expression} may be blank because an empty condition is a valid unconditional
 * transition. An absent value is still a malformed request.
 */
public record ExpressionValidationRequest(
    @NotNull(message = "expression is required") String expression) {}
