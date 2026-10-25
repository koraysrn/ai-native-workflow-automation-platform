package com.awap.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.awap.common.api.AwapException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Negative webhook paths: 5xx target, unreachable target and SSRF URL variants. */
class WebhookClientNegativeTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private WebhookClient client() {
    return new WebhookClient(
        new WebhookSigner(),
        mock(SsrfGuard.class),
        mock(OutboundAllowlist.class),
        "s3cret",
        HttpClient.newHttpClient());
  }

  @Test
  void targetReturning500YieldsBadGateway() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();

    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    assertThatThrownBy(() -> client().post(url, "{}"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  @Test
  void unreachableTargetYieldsBadGateway() throws Exception {
    // Bind then release a port so the outbound connection is refused deterministically.
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    int port = server.getAddress().getPort();
    server.stop(0);
    server = null;

    assertThatThrownBy(() -> client().post("http://127.0.0.1:" + port + "/hook", "{}"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  @Test
  void ssrfGuardRejectsMalformedUrl() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("not-a-url")).isInstanceOf(AwapException.class);
  }

  @Test
  void ssrfGuardRejectsUrlWithoutHost() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("http:///hook")).isInstanceOf(AwapException.class);
  }

  @Test
  void ssrfGuardRejectsUnresolvableHost() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("http://this-host-does-not-exist.invalid/hook"))
        .isInstanceOf(AwapException.class);
  }
}
