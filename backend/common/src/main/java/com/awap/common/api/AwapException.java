package com.awap.common.api;

import org.springframework.http.HttpStatus;

/** Base application exception carrying an AWAP error code and an HTTP status. */
public class AwapException extends RuntimeException {

  private final ErrorCode errorCode;
  private final HttpStatus status;

  public AwapException(ErrorCode errorCode, HttpStatus status, String detail) {
    super(detail);
    this.errorCode = errorCode;
    this.status = status;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public HttpStatus status() {
    return status;
  }
}
