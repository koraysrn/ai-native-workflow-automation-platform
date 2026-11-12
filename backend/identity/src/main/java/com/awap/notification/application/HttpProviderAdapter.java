package com.awap.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared HTTP transport for provider-agnostic SMS and push gateways (issue #204).
 *
 * <p>One bounded POST per attempt. The response is treated as a delivery receipt: a 2xx returns the
 * provider message id (looked up in the usual id fields), 4xx is permanent unless it is a throttle
 * (408/429), and 5xx or an IO/timeout failure stays retryable so the retry scheduler finishes the
 * delivery. A 4xx whose body mentions a bounce, complaint or suppression blocks the recipient.
 *
 * <p>The bearer token is read from configuration (env/Vault) and is never logged.
 */
abstract class HttpProviderAdapter implements NotificationChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(HttpProviderAdapter.class);

  private static final List<String> MESSAGE_ID_FIELDS =
      List.of("id", "message_id", "messageId", "name");

  private static final List<String> BLOCKED_RECIPIENT_MARKERS =
      List.of("bounce", "complaint", "suppressed", "unsubscribed");

  protected final ObjectMapper objectMapper;

  private final HttpClient httpClient;

  private final String providerName;

  private final boolean enabled;

  private final URI target;

  private final String token;

  private final Duration timeout;

  HttpProviderAdapter(
      String providerName,
      boolean enabled,
      String endpoint,
      String token,
      long timeoutMs,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    this.providerName = providerName;
    this.enabled = enabled;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1L));
    this.token = token;
    if (!enabled) {
      this.target = null;
      return;
    }
    String configuredEndpoint = requireConfigured(providerName, endpoint, token);
    try {
      this.target = URI.create(configuredEndpoint);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "awap.notification.providers."
              + providerName.toLowerCase(Locale.ROOT)
              + ".endpoint is not a valid URI.",
          ex);
    }
  }

  @Override
  public String dispatch(NotificationMessage message) {
    if (!enabled) {
      log.info(
          "{} transport disabled -> tenant={} bytes={}",
          providerName,
          message.tenantId(),
          message.body().length());
      return null;
    }
    HttpRequest request =
        HttpRequest.newBuilder(target)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    payload(message).toString(), StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return providerMessageId(response.body());
      }
      throw classify(providerName, response.statusCode(), response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw NotificationDeliveryException.retryable(
          providerName + "_INTERRUPTED", providerName + " request was interrupted.");
    } catch (IOException ex) {
      throw NotificationDeliveryException.retryable(
          providerName + "_IO", providerName + " request failed: " + ex.getClass().getSimpleName());
    }
  }

  /** The wire payload; subclasses fill the provider-specific fields. */
  protected abstract ObjectNode payload(NotificationMessage message);

  /**
   * Maps an HTTP failure to the retry classification: 4xx is permanent (except throttle/timeout),
   * 5xx and the unexpected 1xx/3xx range stay retryable, and a recipient-level rejection blocks the
   * recipient.
   */
  static NotificationDeliveryException classify(String providerName, int status, String body) {
    if (status == 408 || status == 429) {
      return NotificationDeliveryException.retryable(
          providerName + "_RETRYABLE",
          providerName + " is throttling or timed out (HTTP " + status + ").");
    }
    if (status >= 500) {
      return NotificationDeliveryException.retryable(
          providerName + "_UNAVAILABLE", providerName + " returned HTTP " + status + ".");
    }
    if (status >= 400) {
      return mentionsBlockedRecipient(body)
          ? NotificationDeliveryException.blockedRecipient(
              providerName + "_BOUNCE",
              providerName + " rejected the recipient (HTTP " + status + ").")
          : NotificationDeliveryException.permanent(
              providerName + "_REJECTED",
              providerName + " rejected the request (HTTP " + status + ").");
    }
    return NotificationDeliveryException.permanent(
        providerName + "_UNEXPECTED_STATUS", providerName + " returned HTTP " + status + ".");
  }

  private static boolean mentionsBlockedRecipient(String body) {
    String normalized = body.toLowerCase(Locale.ROOT);
    return BLOCKED_RECIPIENT_MARKERS.stream().anyMatch(normalized::contains);
  }

  private String providerMessageId(String body) {
    if (body.isBlank()) {
      return null;
    }
    JsonNode node;
    try {
      node = objectMapper.readTree(body);
    } catch (JsonProcessingException ex) {
      // A 2xx with a non-JSON body is still a success; there is simply no id to record.
      return null;
    }
    for (String field : MESSAGE_ID_FIELDS) {
      JsonNode value = node.get(field);
      if (value != null && !value.isNull() && !value.asText().isBlank()) {
        return value.asText();
      }
    }
    return null;
  }

  /** Returns the trimmed endpoint or fails fast; never returns null. */
  private static String requireConfigured(String providerName, String endpoint, String token) {
    if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
      throw new IllegalStateException(
          "awap.notification.providers."
              + providerName.toLowerCase(Locale.ROOT)
              + " is enabled but endpoint/token are missing; supply them from the environment or"
              + " Vault.");
    }
    return endpoint.trim();
  }
}
