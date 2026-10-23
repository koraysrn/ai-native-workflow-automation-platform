package com.awap.webhook.infrastructure;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Verifies inbound webhook requests: constant-time HMAC comparison, timestamp-skew rejection and
 * replay protection using a bounded LRU cache of already-seen signatures.
 */
@Component
public class WebhookVerifier {

  private static final int MAX_REPLAY_CACHE_SIZE = 10_000;

  private final WebhookSigner signer;
  private final long maxTimestampSkewMillis;

  /** Bounded, synchronized LRU cache of signatures already accepted (replay protection). */
  private final Map<String, Boolean> replayedSignatures =
      Collections.synchronizedMap(
          new LinkedHashMap<>(MAX_REPLAY_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
              return size() > MAX_REPLAY_CACHE_SIZE;
            }
          });

  public WebhookVerifier(
      WebhookSigner signer,
      @Value("${awap.webhook.max-timestamp-skew-ms:300000}") long maxTimestampSkewMillis) {
    this.signer = signer;
    this.maxTimestampSkewMillis = maxTimestampSkewMillis;
  }

  /** Throws unless the signature matches and the timestamp is within tolerance and not replayed. */
  public void verify(String secret, String payload, long timestamp, String signature) {
    if (signature == null || signature.isBlank()) {
      throw invalid("Webhook signature is missing.");
    }
    long skew = Math.abs(System.currentTimeMillis() - timestamp);
    if (skew > maxTimestampSkewMillis) {
      throw invalid("Webhook timestamp is too old or in the future.");
    }
    String expected = signer.sign(secret, payload, timestamp);
    if (!constantTimeEquals(expected, signature)) {
      throw invalid("Webhook signature is invalid.");
    }
    if (replayedSignatures.putIfAbsent(signature, Boolean.TRUE) != null) {
      throw invalid("Webhook request is a replay.");
    }
  }

  private boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private AwapException invalid(String detail) {
    return new AwapException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, detail);
  }
}
