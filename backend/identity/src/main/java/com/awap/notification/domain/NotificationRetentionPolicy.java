package com.awap.notification.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Retention windows for the notification delivery ledger.
 *
 * <p>Read acknowledgements are cheap to age out, while an unread notification that disappears
 * silently is a lost user action. The two windows are therefore independent: a read delivery is
 * purged once its {@code read_at} is older than {@code readRetention}, and an unread delivery only
 * once its {@code created_at} is older than {@code unreadRetention}.
 */
public record NotificationRetentionPolicy(Duration readRetention, Duration unreadRetention) {

  public NotificationRetentionPolicy {
    if (readRetention == null || unreadRetention == null) {
      throw new IllegalArgumentException("Retention windows are mandatory.");
    }
    if (readRetention.isNegative() || unreadRetention.isNegative()) {
      throw new IllegalArgumentException("Retention windows cannot be negative.");
    }
    if (unreadRetention.compareTo(readRetention) < 0) {
      throw new IllegalArgumentException("Unread retention cannot be shorter than read retention.");
    }
  }

  /** Inclusive-exclusive cutoff below which read deliveries may be purged. */
  public Instant readCutoff(Instant now) {
    return now.minus(readRetention);
  }

  /** Cutoff below which never-read deliveries may be purged. */
  public Instant unreadCutoff(Instant now) {
    return now.minus(unreadRetention);
  }

  /** Whether a read delivery acknowledged at {@code readAt} may be purged. */
  public boolean mayPurgeRead(Instant readAt, Instant now) {
    return readAt.isBefore(readCutoff(now));
  }

  /** Whether an unread delivery created at {@code createdAt} may be purged. */
  public boolean mayPurgeUnread(Instant createdAt, Instant now) {
    return createdAt.isBefore(unreadCutoff(now));
  }
}
