package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Renders {@code {{variable}}} placeholders from a variables JSON map. Every substituted value is
 * HTML-escaped so untrusted form/context data cannot inject markup into a notification template.
 */
@Component
public class NotificationTemplateRenderer {

  private final ObjectMapper objectMapper;

  public NotificationTemplateRenderer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String render(String template, String variablesJson) {
    Map<String, String> variables = parseVariables(variablesJson);
    String rendered = template;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      rendered =
          rendered.replace(
              "{{" + entry.getKey() + "}}", escapeHtml(String.valueOf(entry.getValue())));
    }
    return rendered;
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
