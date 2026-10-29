package com.awap.ai.infrastructure;

import com.awap.ai.application.AiBackendClient;
import com.awap.ai.application.LocalHeuristicAiBackend;
import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible provider adapter (issue #132). Enabled only when {@code
 * awap.ai.provider=openai}; every call carries the caller's prompt and is bounded by the configured
 * timeout. Transport failures, 429 rate limits and 5xx answers fall back to the local heuristic
 * provider so a provider outage cannot fail the workflow outright.
 */
@Component
@Primary
@ConditionalOnProperty(name = "awap.ai.provider", havingValue = "openai")
public class OpenAiCompatibleAiBackend implements AiBackendClient {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final LocalHeuristicAiBackend fallback;
  private final MeterRegistry meterRegistry;
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final Duration timeout;

  @Autowired
  public OpenAiCompatibleAiBackend(
      @Value("${awap.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${awap.ai.openai.api-key:}") String apiKey,
      @Value("${awap.ai.openai.model:gpt-4o-mini}") String model,
      @Value("${awap.ai.timeout-ms:5000}") long timeoutMs,
      ObjectMapper objectMapper,
      LocalHeuristicAiBackend fallback,
      MeterRegistry meterRegistry) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(Math.max(timeoutMs, 1), 2_000)))
            .build(),
        objectMapper,
        fallback,
        meterRegistry,
        baseUrl,
        apiKey,
        model,
        Duration.ofMillis(Math.max(timeoutMs, 1)));
  }

  /** Visible for tests so a local HTTP server can stand in for the provider. */
  public OpenAiCompatibleAiBackend(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      LocalHeuristicAiBackend fallback,
      MeterRegistry meterRegistry,
      String baseUrl,
      String apiKey,
      String model,
      Duration timeout) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.fallback = fallback;
    this.meterRegistry = meterRegistry;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.timeout = timeout;
  }

  @Override
  public String complete(String prompt, int maxTokens) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_GATEWAY,
          "AI provider API key is not configured.");
    }
    try {
      String payload =
          objectMapper.writeValueAsString(
              Map.of(
                  "model",
                  model,
                  "max_tokens",
                  maxTokens,
                  "messages",
                  List.of(Map.of("role", "user", "content", prompt))));
      HttpRequest request =
          HttpRequest.newBuilder(endpoint())
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 429 || response.statusCode() >= 500) {
        return degrade("status-" + response.statusCode(), prompt, maxTokens);
      }
      if (response.statusCode() / 100 != 2) {
        throw new AwapException(
            ErrorCode.GENERIC_INTERNAL_ERROR,
            HttpStatus.BAD_GATEWAY,
            "AI provider rejected the request with status " + response.statusCode() + ".");
      }
      String content =
          objectMapper
              .readTree(response.body())
              .path("choices")
              .path(0)
              .path("message")
              .path("content")
              .asText("");
      if (content.isBlank()) {
        return degrade("empty-content", prompt, maxTokens);
      }
      meterRegistry.counter("awap.ai.provider.requests", "result", "success").increment();
      return content;
    } catch (IOException ex) {
      return degrade("transport", prompt, maxTokens);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return degrade("interrupted", prompt, maxTokens);
    }
  }

  private String degrade(String reason, String prompt, int maxTokens) {
    meterRegistry.counter("awap.ai.provider.fallback", "reason", reason).increment();
    return fallback.complete(prompt, maxTokens);
  }

  private URI endpoint() {
    String normalized =
        baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return URI.create(normalized + "/chat/completions");
  }
}
