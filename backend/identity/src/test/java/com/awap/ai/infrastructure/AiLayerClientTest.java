package com.awap.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.correlation.CorrelationId;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AiLayerClientTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    MDC.clear();
  }

  private AiLayerClient client() {
    return new AiLayerClient(HttpClient.newHttpClient(), baseUrl, Duration.ofSeconds(2));
  }

  private void respond(int status, String echoedCorrelationId, AtomicReference<String> captured) {
    server.createContext(
        "/api/v1/health",
        exchange -> {
          captured.set(exchange.getRequestHeaders().getFirst(CorrelationId.HEADER));
          byte[] payload = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          if (echoedCorrelationId != null) {
            exchange.getResponseHeaders().add(CorrelationId.HEADER, echoedCorrelationId);
          }
          exchange.sendResponseHeaders(status, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
  }

  @Test
  void forwardsTheCorrelationIdAndAcceptsTheEcho() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    respond(200, "corr-1", captured);
    MDC.put(CorrelationId.MDC_KEY, "corr-1");

    AiLayerClient.AiLayerProbe probe = client().probe();

    assertThat(captured.get()).isEqualTo("corr-1");
    assertThat(probe.sentCorrelationId()).isEqualTo("corr-1");
    assertThat(probe.receivedCorrelationId()).isEqualTo("corr-1");
    assertThat(probe.correlated()).isTrue();
    assertThat(probe.healthy()).isTrue();
  }

  @Test
  void generatesAnIdWhenNoRequestContextExists() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    respond(200, null, captured);

    AiLayerClient.AiLayerProbe probe = client().probe();

    assertThat(CorrelationId.isValid(probe.sentCorrelationId())).isTrue();
    assertThat(captured.get()).isEqualTo(probe.sentCorrelationId());
    assertThat(probe.correlated()).isFalse();
    assertThat(probe.healthy()).isFalse();
  }

  @Test
  void reportsAMismatchedEchoAsNotCorrelated() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    respond(200, "different", captured);
    MDC.put(CorrelationId.MDC_KEY, "corr-1");

    AiLayerClient.AiLayerProbe probe = client().probe();

    assertThat(probe.correlated()).isFalse();
    assertThat(probe.healthy()).isFalse();
  }

  @Test
  void failsWhenTheLayerIsUnreachable() {
    server.stop(0);
    assertThatThrownBy(() -> client().probe()).isInstanceOf(IOException.class);
  }

  @Test
  void buildsItsOwnHttpClientFromConfigurationAndNormalisesATrailingSlash() {
    AiLayerClient configured = new AiLayerClient("http://127.0.0.1:1/", 250);
    assertThatThrownBy(configured::probe).isInstanceOf(IOException.class);
  }
}
