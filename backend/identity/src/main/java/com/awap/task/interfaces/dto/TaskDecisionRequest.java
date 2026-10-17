package com.awap.task.interfaces.dto;

/** Request body for approve/reject decisions; the comment is optional. */
public record TaskDecisionRequest(String comment) {}
