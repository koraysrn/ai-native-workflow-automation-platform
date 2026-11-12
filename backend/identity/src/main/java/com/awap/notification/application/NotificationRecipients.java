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
 */
public final class NotificationRecipients {

  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+'-]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$");

  /** E.164: a leading plus, a non-zero country digit and up to 15 digits in total. */
  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

  /** FCM registration tokens and APNs device tokens are URL-safe and at least 16 characters. */
  private static final Pattern PUSH_TOKEN = Pattern.compile("^[A-Za-z0-9_.:\\-]{16,4096}$");

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
      case EMAIL -> EMAIL.matcher(recipient).matches();
      case SMS -> E164.matcher(recipient).matches();
      case PUSH -> PUSH_TOKEN.matcher(recipient).matches();
      case WEB, WEBHOOK -> true;
    };
  }
}
