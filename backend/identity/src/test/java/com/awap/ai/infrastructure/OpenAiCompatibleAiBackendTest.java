package com.awap.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.ai.application.LocalHeuristicAiBackend;
import com.awap.common.api.AwapException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleAiBackendTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final LocalHeuristicAiBackend fallback = new LocalHeuristicAiBackend();

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private OpenAiCompatibleAiBackend backend(String apiKey, String url) {
    return new OpenAiCompatibleAiBackend(
        HttpClient.newHttpClient(),
        mapper,
        fallback,
        registry,
        url,
        apiKey,
        "test-model",
        Duration.ofSeconds(2));
  }

  private void respond(int status, String body, Consumer<HttpExchange> capture) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capture.accept(exchange);
          byte[] payload = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
  }

  @Test
  void callsTheChatCompletionsEndpointAndReturnsTheContent() {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    respond(
        200,
        "{\"choices\":[{\"message\":{\"content\":\"FINANCE\"}}]}",
        exchange -> {
          method.set(exchange.getRequestMethod());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          try {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          } catch (IOException ex) {
            throw new IllegalStateException(ex);
          }
        });

    String content = backend("sk-test", baseUrl).complete("classify: invoice payment", 16);

    assertThat(content).isEqualTo("FINANCE");
    assertThat(method.get()).isEqualTo("POST");
    assertThat(authorization.get()).isEqualTo("Bearer sk-test");
    assertThat(body.get())
        .contains("\"model\":\"test-model\"")
        .contains("classify: invoice payment");
    assertThat(registry.get("awap.ai.provider.requests").tag("result", "success").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void rateLimitsAndServerErrorsFallBackToTheLocalProvider() throws Exception {
    respond(429, "{}", exchange -> {});
    assertThat(backend("sk-test", baseUrl).complete("classify: invoice payment", 16))
        .isEqualTo("FINANCE");

    stopServer();
    startServer();
    respond(503, "{}", exchange -> {});
    assertThat(backend("sk-test", baseUrl).complete("classify: contract review", 16))
        .isEqualTo("LEGAL");

    assertThat(
            registry.get("awap.ai.provider.fallback").tag("reason", "status-429").counter().count())
        .isEqualTo(1.0);
    assertThat(
            registry.get("awap.ai.provider.fallback").tag("reason", "status-503").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void blankCompletionFallsBack() {
    respond(200, "{\"choices\":[]}", exchange -> {});

    assertThat(backend("sk-test", baseUrl).complete("classify: invoice", 16)).isEqualTo("FINANCE");
    assertThat(
            registry
                .get("awap.ai.provider.fallback")
                .tag("reason", "empty-content")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void authErrorsSurfaceInsteadOfFallingBack() {
    respond(401, "{\"error\":\"invalid key\"}", exchange -> {});

    assertThatThrownBy(() -> backend("sk-test", baseUrl).complete("classify: invoice", 16))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(502));
  }

  @Test
  void missingApiKeyFailsClosedWithoutCallingTheProvider() {
    assertThatThrownBy(() -> backend("", baseUrl).complete("classify: invoice", 16))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(502));
  }

  @Test
  void transportFailuresFallBack() {
    String unreachable = "http://127.0.0.1:1/v1";

    assertThat(backend("sk-test", unreachable).complete("classify: invoice", 16))
        .isEqualTo("FINANCE");
    assertThat(
            registry.get("awap.ai.provider.fallback").tag("reason", "transport").counter().count())
        .isEqualTo(1.0);
  }
}
