package com.awap.webhook.infrastructure;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.webhook.application.WebhookAttemptResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Sends HMAC-signed outbound webhook requests with SSRF and allowlist enforcement. */
@Component
public class WebhookClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final WebhookSigner signer;
  private final SsrfGuard ssrfGuard;
  private final OutboundAllowlist allowlist;
  private final String secret;
  private final HttpClient httpClient;

  @Autowired
  public WebhookClient(
      WebhookSigner signer,
      SsrfGuard ssrfGuard,
      OutboundAllowlist allowlist,
      @Value("${awap.webhook.secret:}") String secret) {
    this(
        signer,
        ssrfGuard,
        allowlist,
        secret,
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  WebhookClient(
      WebhookSigner signer,
      SsrfGuard ssrfGuard,
      OutboundAllowlist allowlist,
      String secret,
      HttpClient httpClient) {
    this.signer = signer;
    this.ssrfGuard = ssrfGuard;
    this.allowlist = allowlist;
    this.secret = secret;
    this.httpClient = httpClient;
  }

  /** Posts the payload and returns the response body. Throws on non-2xx or transport failure. */
  public String post(String url, String payload) {
    return post(url, payload, null, READ_TIMEOUT);
  }

  /**
   * Posts the payload with a stable idempotency key and a per-call timeout. The key lets external
   * receivers de-duplicate retries triggered by node-level recovery.
   */
  public String post(String url, String payload, String idempotencyKey, Duration timeout) {
    WebhookAttemptResult result = attempt(url, payload, idempotencyKey, timeout);
    if (result.transportFailure()) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_GATEWAY,
          "Webhook request failed: " + result.error().orElse("unknown transport error"));
    }
    if (result.statusCode() >= 400) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_GATEWAY,
          "Webhook target returned status " + result.statusCode() + ".");
    }
    return result.body();
  }

  /**
   * Performs exactly one signed request and reports the outcome instead of throwing for HTTP error
   * statuses, so the retry policy can classify them. The SSRF guard, the outbound allowlist and the
   * HMAC signature are re-applied on every call (i.e. on every retry).
   */
  public WebhookAttemptResult attempt(
      String url, String payload, String idempotencyKey, Duration timeout) {
    ssrfGuard.assertSafe(url);
    allowlist.assertAllowed(url);
    long timestamp = System.currentTimeMillis();
    String signature = signer.sign(secret, payload, timestamp);

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout == null ? READ_TIMEOUT : timeout)
            .header("Content-Type", "application/json")
            .header("X-Awap-Signature", signature)
            .header("X-Awap-Timestamp", String.valueOf(timestamp))
            .POST(HttpRequest.BodyPublishers.ofString(payload));
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    HttpRequest request = builder.build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return WebhookAttemptResult.httpError(
            response.statusCode(),
            response.body(),
            response.headers().firstValue("Retry-After").orElse(null));
      }
      return WebhookAttemptResult.success(response.statusCode(), response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return WebhookAttemptResult.transportFailure("Webhook request was interrupted.");
    } catch (Exception ex) {
      return WebhookAttemptResult.transportFailure(
          "Webhook request failed: " + ex.getClass().getSimpleName());
    }
  }
}
