package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.ErrorCode;
import com.awap.common.api.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Verifies a concurrent approval race becomes a 409 instead of a 500. */
class ConcurrencyExceptionHandlerTest {

  private final ConcurrencyExceptionHandler handler = new ConcurrencyExceptionHandler();

  @Test
  void mapsOptimisticLockFailureToConflict() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/tasks/42/approve");

    ResponseEntity<ProblemDetails> response =
        handler.handleOptimisticLock(
            new OptimisticLockingFailureException("Row was updated by another transaction"),
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().status()).isEqualTo(409);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.RESOURCE_CONFLICT.code());
    assertThat(response.getBody().detail())
        .isEqualTo(ErrorCode.RESOURCE_CONFLICT.defaultTitle())
        .doesNotContain("another transaction");
    assertThat(response.getBody().instance()).hasToString("/api/v1/tasks/42/approve");
    assertThat(response.getBody().timestamp()).isNotNull();
  }
}
