package com.awap.notification.application;

/**
 * A provider failure that carries its retry classification (issue #204).
 *
 * <p>Permanent failures (4xx, authentication, hard bounce, complaint, invalid recipient) are never
 * retried and are dead-lettered immediately. Everything else — timeouts, 5xx, IO errors — stays
 * retryable and is handed to {@code NotificationRetryWorker}/{@code NotificationRetryScheduler}.
 *
 * <p>A <em>blocked recipient</em> is a permanent bounce or complaint: the recipient is recorded in
 * the suppression store so later deliveries are dropped before the provider call.
 */
public class NotificationDeliveryException extends RuntimeException {

  private final String reason;

  private final boolean permanent;

  private final boolean recipientBlocked;

  public NotificationDeliveryException(
      String reason, String detail, boolean permanent, boolean recipientBlocked) {
    super(detail == null || detail.isBlank() ? reason : detail);
    this.reason = reason;
    this.permanent = permanent;
    this.recipientBlocked = recipientBlocked;
  }

  /** Permanent failure such as a rejected payload or a misconfigured provider. */
  public static NotificationDeliveryException permanent(String reason, String detail) {
    return new NotificationDeliveryException(reason, detail, true, false);
  }

  /** Retryable failure such as a timeout, a 5xx or an IO error. */
  public static NotificationDeliveryException retryable(String reason, String detail) {
    return new NotificationDeliveryException(reason, detail, false, false);
  }

  /** Hard bounce or spam complaint: permanent, and the recipient is suppressed afterwards. */
  public static NotificationDeliveryException blockedRecipient(String reason, String detail) {
    return new NotificationDeliveryException(reason, detail, true, true);
  }

  /**
   * The recipient is already on the suppression list, so this delivery is dropped without touching
   * the provider or the suppression list again.
   */
  public static NotificationDeliveryException alreadySuppressed(String detail) {
    return new NotificationDeliveryException("RECIPIENT_SUPPRESSED", detail, true, false);
  }

  public String getReason() {
    return reason;
  }

  public boolean isPermanent() {
    return permanent;
  }

  public boolean isRecipientBlocked() {
    return recipientBlocked;
  }
}
