package com.awap.common.correlation;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation-ID contract shared by HTTP, Kafka, the outbox and the AI layer (issue #191).
 *
 * <p>The ID is transported in the {@value #HEADER} header and stored in the {@value #MDC_KEY} MDC
 * key, which the structured JSON encoder emits on every log line. A client-supplied value is only
 * accepted when it is short and safe; anything else is replaced by a generated UUID so a malicious
 * value cannot inject log lines or overflow a column.
 */
public final class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final int MAX_LENGTH = 64;

  /** Bounded, log-safe character set: letters, digits, '.', '_', ':' and '-'. */
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

  private CorrelationId() {}

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** Returns the candidate when it is safe, otherwise a freshly generated ID. */
  public static String resolve(String candidate) {
    if (candidate == null) {
      return generate();
    }
    String trimmed = candidate.trim();
    if (trimmed.length() > MAX_LENGTH || !SAFE.matcher(trimmed).matches()) {
      return generate();
    }
    return trimmed;
  }

  public static boolean isValid(String candidate) {
    return candidate != null
        && candidate.length() <= MAX_LENGTH
        && SAFE.matcher(candidate).matches();
  }

  public static Optional<String> current() {
    return Optional.ofNullable(MDC.get(MDC_KEY));
  }

  public static String currentOrDefault() {
    return current().orElseGet(CorrelationId::generate);
  }

  public static void set(String correlationId) {
    MDC.put(MDC_KEY, correlationId);
  }

  public static void clear() {
    MDC.remove(MDC_KEY);
  }
}
