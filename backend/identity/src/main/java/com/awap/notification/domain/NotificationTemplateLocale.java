package com.awap.notification.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonicalizes template locales to the {@code language} or {@code language-REGION} form enforced
 * by the {@code notification_templates} check constraint.
 *
 * <p>The canonical form is lowercase language plus uppercase region (for example {@code en} or
 * {@code en-US}). Canonicalizing in one place keeps API validation, cache keys and delivery lookups
 * identical, so a tenant that stores {@code en-US} is resolved with the same key at render time.
 */
public final class NotificationTemplateLocale {

  private static final Pattern LOCALE_PATTERN = Pattern.compile("^[A-Za-z]{2}([_-][A-Za-z]{2})?$");

  private NotificationTemplateLocale() {}

  /** Returns the canonical locale or throws when the value is not a supported locale. */
  public static String canonicalize(String locale) {
    if (locale == null || locale.isBlank()) {
      throw new InvalidNotificationTemplateException("locale is required.");
    }
    String trimmed = locale.trim();
    if (!LOCALE_PATTERN.matcher(trimmed).matches()) {
      throw new InvalidNotificationTemplateException("locale must look like 'en' or 'en-US'.");
    }
    String[] parts = trimmed.replace('_', '-').split("-");
    String language = parts[0].toLowerCase(Locale.ROOT);
    if (parts.length == 1) {
      return language;
    }
    return language + "-" + parts[1].toUpperCase(Locale.ROOT);
  }
}
