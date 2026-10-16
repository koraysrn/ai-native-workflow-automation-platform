package com.awap.identity.infrastructure.security;

import com.awap.common.api.ErrorCode;
import com.awap.common.api.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps method-security access denials to RFC 7807 403 responses. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetails> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ProblemDetails body =
        new ProblemDetails(
            URI.create("https://awap.dev/errors/" + ErrorCode.FORBIDDEN.code()),
            ErrorCode.FORBIDDEN.defaultTitle(),
            HttpStatus.FORBIDDEN.value(),
            "Access denied.",
            URI.create(request.getRequestURI()),
            ErrorCode.FORBIDDEN.code(),
            Instant.now());
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
  }
}
