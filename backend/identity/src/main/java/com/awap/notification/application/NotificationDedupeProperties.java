package com.awap.notification.application;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for notification de-duplication.
 *
 * <pre>
 * awap:
 *   notification:
 *     dedupe-window-seconds: 3600            # default active window
 *     dedupe-template-windows:               # optional per-template overrides
 *       task-assigned: 300
 * </pre>
 *
 * <p>The values are validated by {@link NotificationDedupeWindowResolver}, which also rejects
 * overrides for template keys that do not exist.
 */
@ConfigurationProperties(prefix = "awap.notification")
public class NotificationDedupeProperties {

  private long dedupeWindowSeconds = 3600;

  private Map<String, Long> dedupeTemplateWindows = new LinkedHashMap<>();

  public long getDedupeWindowSeconds() {
    return dedupeWindowSeconds;
  }

  public void setDedupeWindowSeconds(long dedupeWindowSeconds) {
    this.dedupeWindowSeconds = dedupeWindowSeconds;
  }

  public Map<String, Long> getDedupeTemplateWindows() {
    return dedupeTemplateWindows;
  }

  public void setDedupeTemplateWindows(Map<String, Long> dedupeTemplateWindows) {
    this.dedupeTemplateWindows =
        dedupeTemplateWindows == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(dedupeTemplateWindows);
  }
}
