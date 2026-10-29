package com.awap.notification.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves the active de-duplication window for a template and validates the configuration eagerly.
 *
 * <p>Validation rules:
 *
 * <ul>
 *   <li>the default window must be positive;
 *   <li>every per-template override must be positive;
 *   <li>every override must reference a known template key (an unknown key is a typo and fails
 *       startup instead of silently doing nothing).
 * </ul>
 */
@Component
public class NotificationDedupeWindowResolver {

  private final long defaultWindowSeconds;
  private final Map<String, Long> templateWindows;

  public NotificationDedupeWindowResolver(
      NotificationDedupeProperties properties, NotificationTemplateRenderer renderer) {
    this.defaultWindowSeconds =
        requirePositive(
            properties.getDedupeWindowSeconds(), "awap.notification.dedupe-window-seconds");
    this.templateWindows = validateOverrides(properties.getDedupeTemplateWindows(), renderer);
  }

  /** Window in seconds for the given template, falling back to the configured default. */
  public long windowSeconds(String templateKey) {
    if (templateKey == null) {
      return defaultWindowSeconds;
    }
    return templateWindows.getOrDefault(templateKey, defaultWindowSeconds);
  }

  public long defaultWindowSeconds() {
    return defaultWindowSeconds;
  }

  public Map<String, Long> templateWindows() {
    return Map.copyOf(templateWindows);
  }

  private static Map<String, Long> validateOverrides(
      Map<String, Long> overrides, NotificationTemplateRenderer renderer) {
    if (overrides == null || overrides.isEmpty()) {
      return Map.of();
    }
    Set<String> knownTemplates = renderer.knownTemplateKeys();
    Map<String, Long> validated = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : overrides.entrySet()) {
      String templateKey = entry.getKey();
      if (templateKey == null || !knownTemplates.contains(templateKey)) {
        throw new IllegalStateException(
            "Unknown notification template override '"
                + templateKey
                + "'. Known templates: "
                + knownTemplates);
      }
      validated.put(
          templateKey,
          requirePositive(
              entry.getValue(), "awap.notification.dedupe-template-windows." + templateKey));
    }
    return Map.copyOf(validated);
  }

  private static long requirePositive(Long value, String property) {
    if (value == null || value <= 0) {
      throw new IllegalStateException(
          property + " must be a positive number of seconds, but was " + value + ".");
    }
    return value;
  }
}
