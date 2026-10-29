package com.awap.webhook.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of exactly one HTTP attempt. HTTP error statuses are reported instead of thrown so the
 * retry policy (not the transport) decides whether the delivery is retryable.
 */
public record WebhookAttemptResult(
    int statusCode, String body, Optional<String> retryAfterHeader, Optional<String> error) {

  public static final int TRANSPORT_FAILURE = 0;

  public WebhookAttemptResult {
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(retryAfterHeader, "retryAfterHeader");
    Objects.requireNonNull(error, "error");
  }

  public static WebhookAttemptResult success(int statusCode, String body) {
    return new WebhookAttemptResult(statusCode, body, Optional.empty(), Optional.empty());
  }

  public static WebhookAttemptResult httpError(int statusCode, String body, String retryAfter) {
    return new WebhookAttemptResult(
        statusCode, body, Optional.ofNullable(retryAfter), Optional.empty());
  }

  public static WebhookAttemptResult rejected(int statusCode, String reason) {
    return new WebhookAttemptResult(statusCode, "", Optional.empty(), Optional.of(reason));
  }

  public static WebhookAttemptResult transportFailure(String reason) {
    return new WebhookAttemptResult(TRANSPORT_FAILURE, "", Optional.empty(), Optional.of(reason));
  }

  public boolean success() {
    return statusCode >= 200 && statusCode < 300;
  }

  public boolean transportFailure() {
    return statusCode == TRANSPORT_FAILURE;
  }

  /** Response body of a successful attempt, defaulting to an empty JSON object. */
  public String responseBodyOrEmptyObject() {
    return body == null || body.isBlank() ? "{}" : body;
  }
}
