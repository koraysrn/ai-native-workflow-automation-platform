package com.awap.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Central exception handler returning RFC 7807 Problem Details. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(AwapException.class)
  public ResponseEntity<ProblemDetails> handleAwapException(
      AwapException ex, HttpServletRequest request) {
    String detail = ex.getMessage() == null ? ex.errorCode().defaultTitle() : ex.getMessage();
    ProblemDetails body = build(ex.errorCode(), ex.status(), detail, request.getRequestURI());
    return ResponseEntity.status(ex.status()).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetails> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    ProblemDetails body =
        build(
            ErrorCode.VALIDATION_FAILED,
            HttpStatus.BAD_REQUEST,
            "One or more request fields are invalid.",
            request.getRequestURI());
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
    LOGGER.error("Unhandled exception for {}", request.getRequestURI(), ex);
    ProblemDetails body =
        build(
            ErrorCode.GENERIC_INTERNAL_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred.",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  private ProblemDetails build(ErrorCode code, HttpStatus status, String detail, String instance) {
    return new ProblemDetails(
        URI.create("https://awap.dev/errors/" + code.code()),
        code.defaultTitle(),
        status.value(),
        detail,
        URI.create(instance),
        code.code(),
        Instant.now());
  }
}
