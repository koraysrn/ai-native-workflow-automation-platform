package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.InvalidNotificationTemplateException;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.infrastructure.NotificationTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Renders {@code {{variable}}} placeholders from a variables JSON map. Every substituted value is
 * HTML-escaped so untrusted form/context data cannot inject markup into a notification template.
 *
 * <p>Tenant templates are read from the database and cached per tenant/key/locale; the cache is
 * invalidated when the template is edited. Missing or archived rows fall back to the built-in
 * English template and finally to the code default, so a delivery never fails because of a missing
 * override.
 */
@Component
public class NotificationTemplateRenderer {

  static final String CACHE_METRIC = "awap.notification.template.cache";
  static final String FAILURE_METRIC = "awap.notification.template.render.failures";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
  private static final String DEFAULT_SUBJECT = "Notification";

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
  private final NotificationTemplateRepository templateRepository;
  private final MeterRegistry meterRegistry;
  private final Map<CacheKey, Optional<TemplateSnapshot>> cache = new ConcurrentHashMap<>();

  /** Legacy/test constructor: database templates are unavailable. */
  public NotificationTemplateRenderer(ObjectMapper objectMapper) {
    this(objectMapper, null, null);
  }

  @Autowired
  public NotificationTemplateRenderer(
      ObjectMapper objectMapper,
      NotificationTemplateRepository templateRepository,
      MeterRegistry meterRegistry) {
    this.objectMapper = objectMapper;
    this.templateRepository = templateRepository;
    this.meterRegistry = meterRegistry;
  }

  public String render(String templateKey, String variablesJson) {
    return render(templateKey, "en", variablesJson);
  }

  public String render(String templateKey, String locale, String variablesJson) {
    return render(null, templateKey, locale, variablesJson).body();
  }

  /** Renders a stored (or built-in) template for the tenant, returning subject and body. */
  public RenderedNotificationTemplate render(
      UUID tenantId, String templateKey, String locale, String variablesJson) {
    Map<String, String> variables = parseVariables(variablesJson);
    ResolvedTemplate template = resolve(tenantId, templateKey, locale);
    try {
      return new RenderedNotificationTemplate(
          renderText(template.subject(), variables), renderText(template.body(), variables));
    } catch (InvalidNotificationTemplateException ex) {
      countFailure();
      throw new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  /** Substitutes the variables, HTML-escaping every value; unknown placeholders are rejected. */
  public String renderText(String text, Map<String, String> variables) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    List<String> unknown = new ArrayList<>();
    while (matcher.find()) {
      String value = variables.get(matcher.group(1));
      if (value == null) {
        unknown.add(matcher.group(1));
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
      } else {
        matcher.appendReplacement(out, Matcher.quoteReplacement(escapeHtml(value)));
      }
    }
    matcher.appendTail(out);
    if (!unknown.isEmpty()) {
      throw new InvalidNotificationTemplateException(
          "Unknown template placeholders: " + String.join(", ", unknown) + ".");
    }
    return out.toString();
  }

  /** Parses the variables JSON; malformed JSON is a 400 for the caller. */
  public Map<String, String> parseVariables(String variablesJson) {
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

  /** Drops every cached entry for the tenant/template so the next render reads the new revision. */
  public void invalidate(UUID tenantId, String templateKey) {
    if (tenantId == null || templateKey == null) {
      cache.clear();
      return;
    }
    cache.keySet().removeIf(key -> key.matches(tenantId, templateKey));
  }

  private ResolvedTemplate resolve(UUID tenantId, String templateKey, String locale) {
    String key = templateKey == null ? "" : templateKey.trim();
    String normalizedLocale = locale == null ? "en" : locale.toLowerCase(Locale.ROOT);
    if (key.isEmpty()) {
      return builtIn("notification", "en");
    }
    if (key.contains("{{")) {
      return new ResolvedTemplate(DEFAULT_SUBJECT, key);
    }
    if (tenantId != null && templateRepository != null) {
      Optional<TemplateSnapshot> stored = lookup(tenantId, key, normalizedLocale);
      if (stored.isPresent()) {
        return new ResolvedTemplate(stored.get().subject(), stored.get().body());
      }
      if (!"en".equals(normalizedLocale)) {
        Optional<TemplateSnapshot> english = lookup(tenantId, key, "en");
        if (english.isPresent()) {
          return new ResolvedTemplate(english.get().subject(), english.get().body());
        }
      }
    }
    if (TEMPLATES.containsKey(key)) {
      return builtIn(key, normalizedLocale);
    }
    return new ResolvedTemplate(DEFAULT_SUBJECT, key);
  }

  private Optional<TemplateSnapshot> lookup(UUID tenantId, String templateKey, String locale) {
    CacheKey cacheKey = new CacheKey(tenantId, templateKey, locale);
    Optional<TemplateSnapshot> cached = cache.get(cacheKey);
    if (cached != null) {
      countCache("hit");
      return cached;
    }
    countCache("miss");
    Optional<TemplateSnapshot> loaded =
        templateRepository
            .findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenantId, templateKey, locale)
            .map(NotificationTemplateRenderer::snapshot);
    cache.put(cacheKey, loaded);
    return loaded;
  }

  private ResolvedTemplate builtIn(String key, String locale) {
    Map<String, String> translations = TEMPLATES.getOrDefault(key, TEMPLATES.get("notification"));
    String body = translations.getOrDefault(locale, translations.getOrDefault("en", "{{message}}"));
    return new ResolvedTemplate(DEFAULT_SUBJECT, body);
  }

  private static TemplateSnapshot snapshot(NotificationTemplate template) {
    return new TemplateSnapshot(template.getSubject(), template.getBody());
  }

  private void countCache(String result) {
    if (meterRegistry != null) {
      meterRegistry.counter(CACHE_METRIC, "result", result).increment();
    }
  }

  private void countFailure() {
    if (meterRegistry != null) {
      meterRegistry.counter(FAILURE_METRIC).increment();
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

  /** Rendered subject/body pair ready for dispatch. */
  public record RenderedNotificationTemplate(String subject, String body) {}

  private record ResolvedTemplate(String subject, String body) {}

  private record TemplateSnapshot(String subject, String body) {}

  private record CacheKey(UUID tenantId, String templateKey, String locale) {
    boolean matches(UUID tenant, String key) {
      return tenantId.equals(tenant) && templateKey.equals(key);
    }
  }
}
