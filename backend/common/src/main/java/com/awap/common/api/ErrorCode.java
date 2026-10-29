package com.awap.common.api;

/** Catalog of error codes in the {@code AWAP-<SERVICE>-<CODE>} format. */
public enum ErrorCode {
  GENERIC_INTERNAL_ERROR("AWAP-COMMON-0001", "An unexpected error occurred."),
  VALIDATION_FAILED("AWAP-COMMON-0002", "Request validation failed."),
  RESOURCE_NOT_FOUND("AWAP-COMMON-0003", "The requested resource was not found."),
  UNAUTHORIZED("AWAP-COMMON-0004", "Authentication is required."),
  FORBIDDEN("AWAP-COMMON-0005", "Access to the requested resource is forbidden."),
  RESOURCE_CONFLICT("AWAP-COMMON-0006", "The resource was changed by another request."),
  INVALID_CREDENTIALS("AWAP-IDN-0001", "Invalid credentials."),
  TENANT_ALREADY_EXISTS("AWAP-IDN-0002", "A tenant with this slug already exists."),
  REFRESH_TOKEN_INVALID("AWAP-IDN-0003", "The refresh token is invalid or expired."),
  TENANT_NOT_FOUND("AWAP-IDN-0004", "The tenant was not found."),
  ACCOUNT_LOCKED("AWAP-IDN-0005", "Account is temporarily locked."),
  QUOTA_EXCEEDED("AWAP-IDN-0006", "Tenant quota exceeded."),
  WORKFLOW_INVALID_DEFINITION("AWAP-ENG-0001", "The workflow definition is invalid."),
  WORKFLOW_NOT_PUBLISHED("AWAP-ENG-0002", "The workflow definition is not published."),
  WORKFLOW_STATE_CONFLICT("AWAP-ENG-0003", "The workflow state transition is not allowed."),
  REPLAY_DIVERGENCE(
      "AWAP-ENG-0004", "The replayed execution log diverges from the persisted state."),
  REPLAY_CONFLICT("AWAP-ENG-0005", "A replay is already running for this workflow instance."),
  REPLAY_LOG_TOO_LARGE("AWAP-ENG-0006", "The execution log exceeds the replay row limit."),
  REPLAY_TIMEOUT("AWAP-ENG-0007", "The execution log replay exceeded its time budget."),
  REPLAY_LOG_UNAVAILABLE("AWAP-ENG-0008", "The execution log could not be read for replay."),
  TASK_NOT_FOUND("AWAP-TASK-0001", "The task was not found."),
  TASK_ACCESS_DENIED("AWAP-TASK-0002", "The task is assigned to another user."),
  TASK_STATE_CONFLICT("AWAP-TASK-0003", "The task state transition is not allowed."),
  FORM_INVALID_SCHEMA("AWAP-FORM-0001", "The form schema is invalid."),
  ORG_NOT_FOUND("AWAP-ORG-0001", "The organization resource was not found."),
  FILE_NOT_FOUND("AWAP-FILE-0001", "The file was not found."),
  FILE_SCAN_REJECTED("AWAP-FILE-0002", "The file was rejected by antivirus scanning."),
  FILE_TOO_LARGE("AWAP-FILE-0003", "The file exceeds the maximum allowed size."),
  FILE_FORMAT_UNSUPPORTED("AWAP-FILE-0004", "The file format is not supported.");

  private final String code;
  private final String defaultTitle;

  ErrorCode(String code, String defaultTitle) {
    this.code = code;
    this.defaultTitle = defaultTitle;
  }

  public String code() {
    return code;
  }

  public String defaultTitle() {
    return defaultTitle;
  }
}
