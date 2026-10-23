package com.awap.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import org.junit.jupiter.api.Test;

/** Tests inbound webhook signature verification: skew, constant-time comparison and replay. */
class WebhookVerifierTest {

  private static final String SECRET = "s3cret";

  private final WebhookSigner signer = new WebhookSigner();

  @Test
  void acceptsValidSignature() {
    WebhookVerifier verifier = new WebhookVerifier(signer, 300_000L);
    long now = System.currentTimeMillis();
    String signature = signer.sign(SECRET, "body", now);

    assertThatCode(() -> verifier.verify(SECRET, "body", now, signature))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongSecret() {
    WebhookVerifier verifier = new WebhookVerifier(signer, 300_000L);
    long now = System.currentTimeMillis();
    String signature = signer.sign(SECRET, "body", now);

    assertThatThrownBy(() -> verifier.verify("other-secret", "body", now, signature))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsMissingSignature() {
    WebhookVerifier verifier = new WebhookVerifier(signer, 300_000L);

    assertThatThrownBy(() -> verifier.verify(SECRET, "body", System.currentTimeMillis(), null))
        .isInstanceOf(AwapException.class);
    assertThatThrownBy(() -> verifier.verify(SECRET, "body", System.currentTimeMillis(), "  "))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsExpiredTimestamp() {
    // 1 second skew tolerance makes a 60-second-old timestamp invalid.
    WebhookVerifier verifier = new WebhookVerifier(signer, 1_000L);
    long stale = System.currentTimeMillis() - 60_000L;
    String signature = signer.sign(SECRET, "body", stale);

    assertThatThrownBy(() -> verifier.verify(SECRET, "body", stale, signature))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsReplayOfSameSignature() {
    WebhookVerifier verifier = new WebhookVerifier(signer, 300_000L);
    long now = System.currentTimeMillis();
    String signature = signer.sign(SECRET, "body", now);

    verifier.verify(SECRET, "body", now, signature);

    assertThatThrownBy(() -> verifier.verify(SECRET, "body", now, signature))
        .isInstanceOf(AwapException.class);
  }
}
