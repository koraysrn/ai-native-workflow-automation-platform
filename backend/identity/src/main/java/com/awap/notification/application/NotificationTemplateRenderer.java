package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Renders {@code {{variable}}} placeholders from a variables JSON map. Every substituted value is
 * HTML-escaped so untrusted form/context data cannot inject markup into a notification template.
 *
 * <p>Templates are keyed and localized in code so the platform ships a working default set without
 * a database round-trip; a raw inline template containing {@code {{...}}} is also accepted.
 */
@Component
public class NotificationTemplateRenderer {

  private static final Map<String, Map<String, String>> TEMPLATES =
      Map.of(
          "welcome",
          Map.of(
              "en", "Hello {{name}}, welcome to AWAP.",
              "tr", "Merhaba {{name}}, AWAP'a hoş geldiniz."),
          "task-assigned",
          Map.of(
              "en", "You have a new task: {{title}}.",
              "tr", "Yeni bir göreviniz var: {{title}}."),
          "approval-requested",
          Map.of(
              "en", "Approval requested for {{title}}.",
              "tr", "{{title}} için onay talep edildi."),
          "workflow-completed",
          Map.of(
              "en", "Workflow {{name}} completed.",
              "tr", "{{name}} süreci tamamlandı."),
          "notification",
          Map.of("en", "{{message}}", "tr", "{{message}}"));

  private final ObjectMapper objectMapper;

  /** Template keys known to this renderer; used to validate per-template configuration. */
  public java.util.Set<String> knownTemplateKeys() {
    return TEMPLATES.keySet();
  }

  public NotificationTemplateRenderer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String render(String templateKey, String variablesJson) {
    return render(templateKey, "en", variablesJson);
  }

  public String render(String templateKey, String locale, String variablesJson) {
    Map<String, String> variables = parseVariables(variablesJson);
    String rendered = resolveTemplate(templateKey, locale);
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      rendered =
          rendered.replace(
              "{{" + entry.getKey() + "}}", escapeHtml(String.valueOf(entry.getValue())));
    }
    return rendered;
  }

  private String resolveTemplate(String templateKey, String locale) {
    if (templateKey == null || templateKey.isBlank()) {
      return TEMPLATES.get("notification").get("en");
    }
    if (templateKey.contains("{{")) {
      return templateKey;
    }
    Map<String, String> translations = TEMPLATES.get(templateKey);
    if (translations == null) {
      return templateKey;
    }
    String normalized = locale == null ? "en" : locale.toLowerCase(Locale.ROOT);
    return translations.getOrDefault(normalized, translations.getOrDefault("en", templateKey));
  }

  private Map<String, String> parseVariables(String variablesJson) {
    if (variablesJson == null || variablesJson.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(variablesJson, new TypeReference<Map<String, String>>() {});
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_REQUEST,
          "Invalid notification variables JSON: " + ex.getMessage());
    }
  }

  private String escapeHtml(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&':
          sb.append('&').append("amp;");
          break;
        case '<':
          sb.append('&').append("lt;");
          break;
        case '>':
          sb.append('&').append("gt;");
          break;
        case '"':
          sb.append('&').append("quot;");
          break;
        case '\'':
          sb.append('&').append("#39;");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.toString();
  }
}
