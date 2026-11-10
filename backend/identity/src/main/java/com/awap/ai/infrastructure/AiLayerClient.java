package com.awap.ai.infrastructure;

import com.awap.common.correlation.CorrelationId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Small HTTP client for the Python AI layer (issue #191). It forwards the current correlation ID in
 * the {@code X-Correlation-Id} header and verifies that the AI layer echoed the same value.
 *
 * <p>The layer has no workflow execution endpoint yet, so the health contract is the real
 * integration point: the backend health indicator uses this client to prove both reachability and
 * correlation-ID round-tripping without inventing an API that does not exist.
 */
@Component
@ConditionalOnProperty(name = "awap.ai.layer.enabled", havingValue = "true")
public class AiLayerClient {

  private final HttpClient httpClient;
  private final String baseUrl;
  private final Duration timeout;

  @Autowired
  public AiLayerClient(
      @Value("${awap.ai.layer.base-url:http://localhost:8000}") String baseUrl,
      @Value("${awap.ai.layer.timeout-ms:2000}") long timeoutMs) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(Math.max(timeoutMs, 1), 2_000)))
            .build(),
        baseUrl,
        Duration.ofMillis(Math.max(timeoutMs, 1)));
  }

  /** Visible for tests so a local HTTP server can stand in for the AI layer. */
  public AiLayerClient(HttpClient httpClient, String baseUrl, Duration timeout) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
  }

  /**
   * Calls the AI layer liveness endpoint and returns the sent and echoed correlation IDs.
   *
   * @throws IOException when the layer is unreachable or the request times out
   */
  public AiLayerProbe probe() throws IOException, InterruptedException {
    String correlationId = CorrelationId.currentOrDefault();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/health"))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header(CorrelationId.HEADER, correlationId)
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String echoed = response.headers().firstValue(CorrelationId.HEADER).orElse("");
    return new AiLayerProbe(response.statusCode(), correlationId, echoed);
  }

  /** Result of one probe; {@link #correlated()} is the assertion the health indicator exposes. */
  public record AiLayerProbe(int status, String sentCorrelationId, String receivedCorrelationId) {

    public boolean correlated() {
      return sentCorrelationId != null && sentCorrelationId.equals(receivedCorrelationId);
    }

    public boolean healthy() {
      return status / 100 == 2 && correlated();
    }
  }
}
