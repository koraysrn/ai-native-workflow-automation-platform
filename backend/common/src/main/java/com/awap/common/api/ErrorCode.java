package com.awap.common.api;

/** Catalog of error codes in the {@code AWAP-<SERVICE>-<CODE>} format. */
public enum ErrorCode {
  GENERIC_INTERNAL_ERROR("AWAP-COMMON-0001", "An unexpected error occurred."),
  VALIDATION_FAILED("AWAP-COMMON-0002", "Request validation failed."),
  RESOURCE_NOT_FOUND("AWAP-COMMON-0003", "The requested resource was not found."),
  UNAUTHORIZED("AWAP-COMMON-0004", "Authentication is required."),
  FORBIDDEN("AWAP-COMMON-0005", "Access to the requested resource is forbidden.");

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
