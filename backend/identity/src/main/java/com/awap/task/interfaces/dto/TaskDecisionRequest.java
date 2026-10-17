package com.awap.task.interfaces.dto;

import jakarta.validation.constraints.Size;

/** Request body for approve/reject decisions; the comment is optional. */
public record TaskDecisionRequest(
    @Size(max = 2000, message = "comment must be at most 2000 characters") String comment) {}
