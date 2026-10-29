package com.awap.notification.domain;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates {@code {{variable}}} placeholders and template size. Framework-free so the rules can be
 * unit tested without Spring.
 */
public final class NotificationTemplateValidator {

  /** Maximum accepted template body size (1 MiB); larger submissions are rejected. */
  public static final int MAX_BODY_BYTES = 1_048_576;

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
  private static final String OPEN = "{{";
  private static final String CLOSE = "}}";

  private NotificationTemplateValidator() {}

  /** Extracts the placeholder names after validating the syntax. */
  public static Set<String> placeholders(String text) {
    Set<String> keys = new LinkedHashSet<>();
    String safe = text == null ? "" : text;
    int opens = count(safe, OPEN);
    int closes = count(safe, CLOSE);
    if (opens != closes) {
      throw new InvalidNotificationTemplateException(
          "Template placeholders are unbalanced: every {{ must be closed with }}.");
    }
    Matcher matcher = PLACEHOLDER.matcher(safe);
    int matched = 0;
    while (matcher.find()) {
      matched++;
      keys.add(matcher.group(1));
    }
    if (matched != opens) {
      throw new InvalidNotificationTemplateException(
          "Template placeholders must look like {{name}} using letters, digits, '.', '_' or '-'.");
    }
    return keys;
  }

  /** Validates size and placeholder syntax of a template subject/body pair. */
  public static void validate(String subject, String body) {
    validateBodySize(body);
    placeholders(subject);
    placeholders(body);
  }

  /** Rejects bodies above {@link #MAX_BODY_BYTES}. */
  public static void validateBodySize(String body) {
    if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
      throw new InvalidNotificationTemplateException(
          "Template body must not exceed " + MAX_BODY_BYTES + " bytes.");
    }
  }

  /** Rejects placeholders that the caller did not supply a value for. */
  public static void validateVariables(String text, Set<String> provided) {
    Set<String> unknown = new LinkedHashSet<>(placeholders(text));
    unknown.removeAll(provided);
    if (!unknown.isEmpty()) {
      throw new InvalidNotificationTemplateException(
          "Unknown template placeholders: " + String.join(", ", unknown) + ".");
    }
  }

  private static int count(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
