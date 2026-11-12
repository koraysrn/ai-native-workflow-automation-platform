package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Recipient validation performed <b>before</b> any provider call (issue #204).
 *
 * <p>A malformed address can never succeed, so it must not reach the provider (and must not be
 * retried). Tenant ownership is structural: every message is built from a tenant-scoped delivery
 * row (or a claim read through the tenant-scoped retry function), so a message without a tenant is
 * rejected here rather than being dispatched.
 *
 * <p>The e-mail check parses the address instead of using one large pattern: a nested quantifier
 * over the domain labels would let a hostile input trigger catastrophic backtracking (Sonar
 * java:S5998). Labels are validated character by character, so validation stays linear.
 */
public final class NotificationRecipients {

  private static final Pattern LOCAL_PART = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}$");

  /** E.164: a leading plus, a non-zero country digit and up to 15 digits in total. */
  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

  /** FCM registration tokens and APNs device tokens are URL-safe and at least 16 characters. */
  private static final Pattern PUSH_TOKEN = Pattern.compile("^[A-Za-z0-9_.:\\-]{16,4096}$");

  private static final int MAX_EMAIL_LENGTH = 254;

  private NotificationRecipients() {}

  /** Validates the message tenant and the per-channel recipient format. */
  public static void validate(NotificationMessage message) {
    validate(message.tenantId(), message.recipient(), message.channel());
  }

  public static void validate(UUID tenantId, String recipient, NotificationChannel channel) {
    if (tenantId == null) {
      throw NotificationDeliveryException.permanent(
          "TENANT_REQUIRED", "Notification message has no tenant; refusing to dispatch.");
    }
    if (recipient == null || recipient.isBlank()) {
      throw NotificationDeliveryException.permanent(
          "RECIPIENT_MISSING", "Notification recipient is empty.");
    }
    if (!matches(channel, recipient.trim())) {
      throw NotificationDeliveryException.permanent(
          "RECIPIENT_INVALID",
          "Recipient is not a valid " + channel + " address (no provider call was made).");
    }
  }

  private static boolean matches(NotificationChannel channel, String recipient) {
    return switch (channel) {
      case EMAIL -> isValidEmail(recipient);
      case SMS -> E164.matcher(recipient).matches();
      case PUSH -> PUSH_TOKEN.matcher(recipient).matches();
      case WEB, WEBHOOK -> true;
    };
  }

  /**
   * Structural e-mail validation: exactly one {@code @}, a bounded local part, at least two domain
   * labels, a valid label shape per RFC 5322 (no leading/trailing hyphen, alphanumerics and hyphens
   * only) and an alphabetic TLD of at least two characters.
   */
  static boolean isValidEmail(String value) {
    if (value.length() > MAX_EMAIL_LENGTH) {
      return false;
    }
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
      return false;
    }
    if (!LOCAL_PART.matcher(value.substring(0, at)).matches()) {
      return false;
    }
    String[] labels = value.substring(at + 1).split("\\.", -1);
    if (labels.length < 2) {
      return false;
    }
    for (String label : labels) {
      if (!isValidLabel(label)) {
        return false;
      }
    }
    String tld = labels[labels.length - 1];
    return tld.length() >= 2 && tld.chars().allMatch(NotificationRecipients::isAsciiLetter);
  }

  private static boolean isValidLabel(String label) {
    if (label.isEmpty() || label.length() > 63) {
      return false;
    }
    if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
      return false;
    }
    return label.chars().allMatch(NotificationRecipients::isLabelCharacter);
  }

  private static boolean isLabelCharacter(int character) {
    return isAsciiLetter(character) || (character >= '0' && character <= '9') || character == '-';
  }

  private static boolean isAsciiLetter(int character) {
    return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
  }
}
