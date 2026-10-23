package com.awap.webhook.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Produces HMAC-SHA256 signatures for outbound webhook requests. */
@Component
public class WebhookSigner {

  /**
   * Signs the payload together with a timestamp so signatures cannot be replayed. The signed
   * message is {@code <timestamp>.<payload>}.
   */
  public String sign(String secret, String payload, long timestamp) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("Could not sign webhook payload.", ex);
    }
  }
}
