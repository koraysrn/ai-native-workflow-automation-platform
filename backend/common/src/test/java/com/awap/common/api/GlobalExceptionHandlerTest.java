package com.awap.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** Verifies RFC 7807 Problem Details mapping for every handled exception type. */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsAwapExceptionToItsStatusAndCode() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/tasks");

    AwapException ex =
        new AwapException(ErrorCode.TASK_ACCESS_DENIED, HttpStatus.FORBIDDEN, "Not your task.");

    ResponseEntity<ProblemDetails> response = handler.handleAwapException(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.TASK_ACCESS_DENIED.code());
    assertThat(response.getBody().detail()).isEqualTo("Not your task.");
  }

  @Test
  void mapsValidationExceptionToBadRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/forms");

    MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);

    ResponseEntity<ProblemDetails> response = handler.handleValidation(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
  }

  @Test
  void mapsGenericExceptionToInternalServerError() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/anything");

    ResponseEntity<ProblemDetails> response =
        handler.handleGeneric(new RuntimeException("boom"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.GENERIC_INTERNAL_ERROR.code());
  }
}
