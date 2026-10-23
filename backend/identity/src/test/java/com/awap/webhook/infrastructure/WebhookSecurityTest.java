package com.awap.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.awap.common.api.AwapException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests HMAC signing, SSRF protection, outbound allowlisting and the HTTP client round-trip. */
class WebhookSecurityTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void signerIsDeterministicAndVariesWithPayload() {
    WebhookSigner signer = new WebhookSigner();
    String first = signer.sign("secret", "payload", 123L);
    String second = signer.sign("secret", "payload", 123L);
    String different = signer.sign("secret", "other", 123L);

    assertThat(first).isEqualTo(second);
    assertThat(first).isNotEqualTo(different);
    assertThat(first).matches("^[0-9a-f]{64}$");
  }

  @Test
  void ssrfGuardBlocksLoopback() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("http://127.0.0.1/hook"))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> guard.assertSafe("http://localhost/hook"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void ssrfGuardBlocksPrivateRanges() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("http://10.0.0.5/hook"))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> guard.assertSafe("http://192.168.1.10/hook"))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> guard.assertSafe("http://169.254.169.254/hook"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void ssrfGuardBlocksIpv4MappedIpv6() {
    SsrfGuard guard = new SsrfGuard();
    assertThatThrownBy(() -> guard.assertSafe("http://[::ffff:127.0.0.1]/hook"))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> guard.assertSafe("http://[::ffff:10.0.0.5]/hook"))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> guard.assertSafe("http://[::ffff:169.254.169.254]/hook"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void allowlistAllowsConfiguredHost() {
    OutboundAllowlist allowlist = new OutboundAllowlist("example.com");
    allowlist.assertAllowed("https://example.com/hook");
  }

  @Test
  void allowlistRejectsUnconfiguredHost() {
    OutboundAllowlist allowlist = new OutboundAllowlist("example.com");
    assertThatThrownBy(() -> allowlist.assertAllowed("https://evil.example.net/hook"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void allowlistDeniesAllWhenEmpty() {
    OutboundAllowlist allowlist = new OutboundAllowlist("");
    assertThatThrownBy(() -> allowlist.assertAllowed("https://example.com/hook"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void webhookClientSignsAndPosts() throws Exception {
    AtomicReference<String> capturedSignature = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Awap-Signature"));
          byte[] body = exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    WebhookSigner signer = new WebhookSigner();
    WebhookClient client =
        new WebhookClient(
            signer,
            mock(SsrfGuard.class),
            mock(OutboundAllowlist.class),
            "s3cret",
            HttpClient.newHttpClient());

    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    String response = client.post(url, "hello");

    assertThat(response).isEqualTo("hello");
    assertThat(capturedSignature.get()).matches("^[0-9a-f]{64}$");
  }
}
