package com.awap.webhook.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.infrastructure.WebhookClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Performs exactly one HTTP attempt for a claimed delivery and records the outcome.
 *
 * <p>Every attempt re-runs the SSRF guard, the outbound allowlist and the HMAC signing inside
 * {@link WebhookClient}; the URL, payload and idempotency key are immutable on the row, so the
 * receiver sees the same {@code Idempotency-Key} and the same signed payload on every attempt.
 * Payloads and URLs (including query strings) are never logged.
 */
@Component
public class WebhookDeliveryAttemptRunner {

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryAttemptRunner.class);

  private final WebhookClient webhookClient;
  private final WebhookDeliveryService deliveryService;
  private final WebhookRetryPolicy policy;
  private final WebhookDeliveryMetrics metrics;
  private final WebhookDeliveryNodePort nodePort;
  private final Duration timeout;

  @Autowired
  public WebhookDeliveryAttemptRunner(
      WebhookClient webhookClient,
      WebhookDeliveryService deliveryService,
      WebhookRetryPolicy policy,
      WebhookDeliveryMetrics metrics,
      WebhookDeliveryNodePort nodePort,
      @Value("${awap.webhook.retry.timeout-seconds:10}") long timeoutSeconds) {
    this(
        webhookClient,
        deliveryService,
        policy,
        metrics,
        nodePort,
        Duration.ofSeconds(Math.max(1, timeoutSeconds)));
  }

  WebhookDeliveryAttemptRunner(
      WebhookClient webhookClient,
      WebhookDeliveryService deliveryService,
      WebhookRetryPolicy policy,
      WebhookDeliveryMetrics metrics,
      WebhookDeliveryNodePort nodePort,
      Duration timeout) {
    this.webhookClient = webhookClient;
    this.deliveryService = deliveryService;
    this.policy = policy;
    this.metrics = metrics;
    this.nodePort = nodePort;
    this.timeout = timeout;
  }

  /** Runs one leased attempt; safe to call concurrently from different scheduler replicas. */
  public void attempt(WebhookDelivery claimed, Instant now) {
    Duration lag = Duration.between(claimed.getNextAttemptAt(), now);
    metrics.recordRetryLag(lag);

    WebhookAttemptResult result = call(claimed);
    int attemptsMade = claimed.getAttempts();
    WebhookRetryPolicy.RetryDecision decision = policy.decide(result, attemptsMade, now);
    boolean willRetry = decision.retry() && claimed.hasAttemptsLeft();
    metrics.recordAttempt(label(result, willRetry), result.statusCode());

    if (result.success()) {
      if (!deliveryService.recordSent(claimed, result.statusCode(), now)) {
        log.warn(
            "Webhook delivery {} lost its lease before completion was recorded.", claimed.getId());
        return;
      }
      nodePort.nodeCompleted(
          claimed.getTenantId(),
          claimed.getWorkflowInstanceId(),
          claimed.getNodeExecutionId(),
          claimed.getId(),
          result.responseBodyOrEmptyObject(),
          result.statusCode());
      return;
    }

    if (willRetry) {
      Instant nextAttemptAt = decision.nextAttemptAt().orElse(now);
      deliveryService.recordRetry(
          claimed, result.statusCode(), errorOf(result), nextAttemptAt, now);
      log.warn(
          "Webhook delivery {} attempt {} failed with status {}; retry scheduled ({}).",
          claimed.getId(),
          attemptsMade,
          result.statusCode(),
          decision.reason());
      return;
    }

    if (!deliveryService.recordDead(claimed, result.statusCode(), errorOf(result), now)) {
      log.warn("Webhook delivery {} lost its lease before DEAD was recorded.", claimed.getId());
      return;
    }
    metrics.recordDead();
    nodePort.deliveryDead(
        claimed.getTenantId(),
        claimed.getWorkflowInstanceId(),
        claimed.getNodeExecutionId(),
        claimed.getId(),
        attemptsMade,
        errorOf(result));
    log.error(
        "Webhook delivery {} is DEAD after {} attempt(s): {}",
        claimed.getId(),
        attemptsMade,
        decision.reason());
  }

  private WebhookAttemptResult call(WebhookDelivery claimed) {
    try {
      return webhookClient.attempt(
          claimed.getTargetUrl(), claimed.getPayloadJson(), claimed.getIdempotencyKey(), timeout);
    } catch (AwapException ex) {
      if (ex.errorCode() == ErrorCode.FORBIDDEN) {
        // SSRF guard / allowlist: permanent and never retried. The URL is deliberately not logged.
        log.warn("Webhook delivery {} blocked by the outbound security policy.", claimed.getId());
        return WebhookAttemptResult.rejected(403, "Outbound webhook blocked by security policy.");
      }
      return WebhookAttemptResult.transportFailure("Webhook request rejected before sending.");
    } catch (RuntimeException ex) {
      return WebhookAttemptResult.transportFailure("Webhook transport failure.");
    }
  }

  private String label(WebhookAttemptResult result, boolean willRetry) {
    if (result.success()) {
      return "sent";
    }
    return willRetry ? "retry" : "dead";
  }

  private String errorOf(WebhookAttemptResult result) {
    if (result.error().isPresent()) {
      return result.error().get();
    }
    return result.transportFailure()
        ? "Transport failure."
        : "Webhook target returned status " + result.statusCode() + ".";
  }
}
