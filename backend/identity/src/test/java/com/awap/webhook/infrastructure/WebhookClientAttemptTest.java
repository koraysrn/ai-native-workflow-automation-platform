package com.awap.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.awap.common.api.AwapException;
import com.awap.webhook.application.WebhookAttemptResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real HTTP outcomes (500, 429 + Retry-After, timeout) and per-attempt security enforcement. */
class WebhookClientAttemptTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private WebhookClient client(HttpClient httpClient) {
    return new WebhookClient(
        new WebhookSigner(),
        mock(SsrfGuard.class),
        mock(OutboundAllowlist.class),
        "s3cret",
        httpClient);
  }

  private String url() {
    return url("/hook");
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  void serverErrorIsReportedAsAnHttpOutcomeInsteadOfAThrownException() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();

    WebhookAttemptResult result =
        client(HttpClient.newHttpClient()).attempt(url(), "{}", "key-1", Duration.ofSeconds(2));

    assertThat(result.success()).isFalse();
    assertThat(result.transportFailure()).isFalse();
    assertThat(result.statusCode()).isEqualTo(500);
    assertThat(result.retryAfterHeader()).isEmpty();
  }

  @Test
  void rateLimitRetryAfterHeaderIsCaptured() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "30");
          exchange.sendResponseHeaders(429, -1);
          exchange.close();
        });
    server.start();

    WebhookAttemptResult result =
        client(HttpClient.newHttpClient()).attempt(url(), "{}", "key-1", Duration.ofSeconds(2));

    assertThat(result.statusCode()).isEqualTo(429);
    assertThat(result.retryAfterHeader()).contains("30");
  }

  @Test
  void successReturnsTheResponseBody() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    WebhookAttemptResult result =
        client(HttpClient.newHttpClient()).attempt(url(), "{}", "key-1", Duration.ofSeconds(2));

    assertThat(result.success()).isTrue();
    assertThat(result.body()).isEqualTo("{\"ok\":true}");
  }

  @Test
  void readTimeoutIsReportedAsATransportFailure() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(2_000L);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    long started = System.nanoTime();
    WebhookAttemptResult result =
        client(HttpClient.newHttpClient())
            .attempt(url("/slow"), "{}", "key-1", Duration.ofMillis(300));
    long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

    assertThat(result.transportFailure()).isTrue();
    assertThat(result.error()).isPresent();
    assertThat(elapsedMs).isLessThan(1_800L);
  }

  @Test
  void connectionRefusedIsReportedAsATransportFailure() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    int port = server.getAddress().getPort();
    server.stop(0);
    server = null;

    WebhookAttemptResult result =
        client(HttpClient.newHttpClient())
            .attempt("http://127.0.0.1:" + port + "/hook", "{}", "key-1", Duration.ofSeconds(2));

    assertThat(result.transportFailure()).isTrue();
  }

  @Test
  void ssrfGuardAndAllowlistRunOnEveryAttempt() throws Exception {
    SsrfGuard guard = mock(SsrfGuard.class);
    OutboundAllowlist allowlist = mock(OutboundAllowlist.class);
    WebhookClient client =
        new WebhookClient(
            new WebhookSigner(), guard, allowlist, "s3cret", HttpClient.newHttpClient());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    String url = url();

    client.attempt(url, "{}", "key-1", Duration.ofSeconds(2));
    client.attempt(url, "{}", "key-1", Duration.ofSeconds(2));

    verify(guard, times(2)).assertSafe(url);
    verify(allowlist, times(2)).assertAllowed(url);
  }

  @Test
  void blockedTargetIsRejectedBeforeAnyRequestIsSent() {
    WebhookClient client =
        new WebhookClient(
            new WebhookSigner(),
            new SsrfGuard(),
            new OutboundAllowlist("hooks.example.com"),
            "s3cret",
            HttpClient.newHttpClient());

    assertThatThrownBy(
            () ->
                client.attempt("http://169.254.169.254/latest", "{}", "key", Duration.ofSeconds(1)))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(
            () ->
                client.attempt("https://evil.example.net/hook", "{}", "key", Duration.ofSeconds(1)))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void legacyPostStillThrowsOnServerErrors() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();

    assertThatThrownBy(() -> client(HttpClient.newHttpClient()).post(url(), "{}"))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("status 503");
  }

  @Test
  void idempotencyKeyIsNotSentWhenAbsent() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> header =
        new java.util.concurrent.atomic.AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          header.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    client(HttpClient.newHttpClient()).attempt(url(), "{}", "", Duration.ofSeconds(2));

    assertThat(header.get()).isNull();
  }
}
