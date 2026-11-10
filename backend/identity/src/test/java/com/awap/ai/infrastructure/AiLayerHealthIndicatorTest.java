package com.awap.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.common.correlation.CorrelationId;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class AiLayerHealthIndicatorTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    MDC.put(CorrelationId.MDC_KEY, "corr-health");
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    MDC.clear();
  }

  private AiLayerHealthIndicator indicator() {
    return new AiLayerHealthIndicator(
        new AiLayerClient(HttpClient.newHttpClient(), baseUrl, Duration.ofSeconds(2)));
  }

  private void respond(int status, String echo) {
    server.createContext(
        "/api/v1/health",
        exchange -> {
          byte[] payload = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
          if (echo != null) {
            exchange.getResponseHeaders().add(CorrelationId.HEADER, echo);
          }
          exchange.sendResponseHeaders(status, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
  }

  @Test
  void isUpWhenTheLayerAnswersAndEchoesTheCorrelationId() {
    respond(200, "corr-health");
    Health health = indicator().health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("correlationId", "corr-health");
  }

  @Test
  void isDownWhenTheEchoIsMissing() {
    respond(200, null);
    Health health = indicator().health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("echoedCorrelationId");
  }

  @Test
  void isDownWhenTheLayerReturnsAnError() {
    respond(503, "corr-health");
    Health health = indicator().health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("status", 503);
  }

  @Test
  void isDownWhenTheLayerIsUnreachable() {
    server.stop(0);
    Health health = indicator().health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("error");
  }
}
