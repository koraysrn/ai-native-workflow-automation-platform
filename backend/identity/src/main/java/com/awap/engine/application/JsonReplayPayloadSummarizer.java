package com.awap.engine.application;

import com.awap.engine.domain.ReplayOutputSummarizer;
import com.awap.engine.domain.ReplayPayloadSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Turns a recorded node output into a redacted summary: length, digest and key names only. The raw
 * values never leave this class, which keeps replay reports free of personal data (KVKK/GDPR).
 */
@Component
public class JsonReplayPayloadSummarizer implements ReplayOutputSummarizer {

  private static final int DIGEST_PREFIX = 16;

  private final ObjectMapper objectMapper;

  public JsonReplayPayloadSummarizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public ReplayPayloadSummary summarize(String payload) {
    if (payload == null || payload.isBlank()) {
      return ReplayPayloadSummary.none();
    }
    try {
      JsonNode node = objectMapper.readTree(payload);
      if (node == null || node.isMissingNode()) {
        return ReplayPayloadSummary.invalid(payload.length());
      }
      List<String> keys = new ArrayList<>();
      if (node.isObject()) {
        node.fieldNames().forEachRemaining(keys::add);
      } else if (node.isArray()) {
        keys.add("array[" + node.size() + "]");
      } else {
        keys.add("scalar");
      }
      keys.sort(String::compareTo);
      return new ReplayPayloadSummary(
          true,
          "len="
              + payload.length()
              + ";sha256="
              + digest(payload).substring(0, DIGEST_PREFIX)
              + ";keys="
              + String.join(",", keys));
    } catch (JsonProcessingException ex) {
      return ReplayPayloadSummary.invalid(payload.length());
    }
  }

  private static String digest(String value) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
