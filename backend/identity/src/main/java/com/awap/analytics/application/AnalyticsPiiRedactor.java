package com.awap.analytics.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Allowlist-based redaction applied immediately before a raw analytics document is written to
 * OpenSearch. Only explicitly mapped, non-personal fields survive; nested objects, collections and
 * any value that looks like an e-mail address, phone number or SSN are removed or masked. Keys are
 * matched case-insensitively and with separators removed, so {@code TenantID}, {@code tenant_id}
 * and {@code tenantId} all map to the canonical {@code tenantId}.
 */
public final class AnalyticsPiiRedactor {

  public static final String REDACTED = "[REDACTED]";

  private static final Map<String, String> ALLOWLIST =
      Map.of(
          "tenantid", "tenantId",
          "eventtype", "eventType",
          "workflowinstanceid", "workflowInstanceId",
          "taskid", "taskId",
          "durationms", "durationMs",
          "timestamp", "timestamp");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE =
      Pattern.compile(
          "(?<![A-Za-z0-9-])(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{1,4}\\)[\\s.-]?)?"
              + "\\d{2,4}[\\s.-]\\d{2,4}(?:[\\s.-]\\d{2,4})?(?![A-Za-z0-9-])");
  private static final Pattern SSN =
      Pattern.compile("(?<![A-Za-z0-9-])\\d{3}-\\d{2}-\\d{4}(?![A-Za-z0-9-])");

  /**
   * Returns a new document containing only allowlisted scalar fields. Unknown and structured fields
   * are dropped entirely, which guarantees nested payloads cannot leak before they reach the bulk
   * request.
   */
  public Map<String, Object> redact(Map<String, Object> document) {
    Map<String, Object> redacted = new LinkedHashMap<>();
    if (document == null) {
      return redacted;
    }
    document.forEach(
        (key, value) -> {
          String canonical = ALLOWLIST.get(normalize(key));
          if (canonical == null || isStructured(value)) {
            return;
          }
          Object safeValue = value instanceof String text ? redactText(text) : value;
          redacted.putIfAbsent(canonical, safeValue);
        });
    return redacted;
  }

  /** Masks PII-looking fragments inside free-form text such as OpenSearch error messages. */
  public String redactText(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String masked = EMAIL.matcher(value).replaceAll(REDACTED);
    masked = PHONE.matcher(masked).replaceAll(REDACTED);
    return SSN.matcher(masked).replaceAll(REDACTED);
  }

  public boolean containsPii(String value) {
    if (value == null) {
      return false;
    }
    return EMAIL.matcher(value).find() || PHONE.matcher(value).find() || SSN.matcher(value).find();
  }

  static String normalize(String key) {
    return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
  }

  private static boolean isStructured(Object value) {
    return value instanceof Map<?, ?>
        || value instanceof Collection<?>
        || (value != null && value.getClass().isArray());
  }
}
