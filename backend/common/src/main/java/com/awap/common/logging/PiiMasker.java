package com.awap.common.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data before it reaches a log appender (issue #191).
 *
 * <p>The policy is deliberately narrow: emails, credentials, bearer tokens, secret-looking
 * key/value pairs, Turkish national IDs and long token-like identifiers are hidden, while
 * operational identifiers (trace IDs, correlation IDs, UUIDs, aggregate IDs) stay readable for
 * debugging. Long opaque identifiers keep an 8-hex-digit SHA-256 prefix so two log lines can still
 * be correlated without exposing the value.
 */
public final class PiiMasker {

  private static final Pattern EMAIL =
      Pattern.compile("(?i)([a-z0-9._%+-])[a-z0-9._%+-]*(@[a-z0-9.-]+\\.[a-z]{2,})");
  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[a-z0-9._~+/=-]{8,}");
  private static final Pattern JSON_SECRET =
      Pattern.compile(
          "(?i)(\"(?:password|secret|token|access_token|refresh_token|authorization|api[_-]?key)\"\\s*:\\s*\")[^\"]*(\")");
  private static final Pattern KV_SECRET =
      Pattern.compile("(?i)\\b(password|secret|token|api[_-]?key)=([^\\s&,;]+)");
  private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{40,}\\b");
  private static final Pattern NATIONAL_ID = Pattern.compile("\\b[1-9]\\d{10}\\b");

  private PiiMasker() {}

  /** Returns the masked text; null and blank values pass through unchanged. */
  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String masked = EMAIL.matcher(text).replaceAll("$1***$2");
    masked = BEARER.matcher(masked).replaceAll("$1***");
    masked = JSON_SECRET.matcher(masked).replaceAll("$1***$2");
    masked = KV_SECRET.matcher(masked).replaceAll("$1=***");
    masked = NATIONAL_ID.matcher(masked).replaceAll("***");
    return hashLongTokens(masked);
  }

  private static String hashLongTokens(String text) {
    Matcher matcher = LONG_TOKEN.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, Matcher.quoteReplacement(hash(matcher.group())));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest).substring(0, 8);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
