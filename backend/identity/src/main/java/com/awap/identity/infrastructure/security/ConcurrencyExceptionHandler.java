package com.awap.identity.infrastructure.security;

import com.awap.common.api.ErrorCode;
import com.awap.common.api.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a lost optimistic-lock race to an RFC 7807 409.
 *
 * <p>Two concurrent approvals of the same task both read the ASSIGNED row and one commit fails with
 * an optimistic-lock exception. Answering 500 would hide a correctly serialized exactly-one-winner
 * outcome behind a server error and make the double-approval race indistinguishable from a defect
 * (issue #201).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConcurrencyExceptionHandler {

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetails> handleOptimisticLock(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    ProblemDetails body =
        new ProblemDetails(
            URI.create("https://awap.dev/errors/" + ErrorCode.RESOURCE_CONFLICT.code()),
            ErrorCode.RESOURCE_CONFLICT.defaultTitle(),
            HttpStatus.CONFLICT.value(),
            ErrorCode.RESOURCE_CONFLICT.defaultTitle(),
            URI.create(request.getRequestURI()),
            ErrorCode.RESOURCE_CONFLICT.code(),
            Instant.now());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
