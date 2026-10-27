package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRetentionPolicyTest {

  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private final NotificationRetentionPolicy policy =
      new NotificationRetentionPolicy(Duration.ofDays(30), Duration.ofDays(180));

  @Test
  void readWindowStartsAtTheAcknowledgement() {
    assertThat(policy.readCutoff(NOW)).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(policy.mayPurgeRead(NOW.minus(Duration.ofDays(31)), NOW)).isTrue();
    assertThat(policy.mayPurgeRead(NOW.minus(Duration.ofDays(2)), NOW)).isFalse();
  }

  @Test
  void unreadWindowIsMuchLongerThanTheReadWindow() {
    assertThat(policy.unreadCutoff(NOW)).isEqualTo(NOW.minus(Duration.ofDays(180)));
    assertThat(policy.unreadCutoff(NOW)).isBefore(policy.readCutoff(NOW));
    assertThat(policy.mayPurgeUnread(NOW.minus(Duration.ofDays(181)), NOW)).isTrue();
    assertThat(policy.mayPurgeUnread(NOW.minus(Duration.ofDays(100)), NOW)).isFalse();
  }

  @Test
  void readWindowExpiresLongBeforeTheUnreadWindow() {
    Instant created = NOW.minus(Duration.ofDays(45));
    Instant read = NOW.minus(Duration.ofDays(40));

    assertThat(policy.mayPurgeRead(read, NOW)).isTrue();
    assertThat(policy.mayPurgeUnread(created, NOW)).isFalse();
  }

  @Test
  void unreadNotificationSurvivesTheReadWindow() {
    Instant created = NOW.minus(Duration.ofDays(45));
    Instant read = NOW.minus(Duration.ofDays(2));

    assertThat(policy.mayPurgeRead(read, NOW)).isFalse();
    assertThat(policy.mayPurgeUnread(created, NOW)).isFalse();
  }

  @Test
  void rejectsNegativeWindows() {
    assertThatThrownBy(
            () -> new NotificationRetentionPolicy(Duration.ofDays(-1), Duration.ofDays(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void rejectsUnreadWindowShorterThanReadWindow() {
    assertThatThrownBy(
            () -> new NotificationRetentionPolicy(Duration.ofDays(30), Duration.ofDays(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unread retention");
  }

  @Test
  void rejectsMissingWindows() {
    assertThatThrownBy(() -> new NotificationRetentionPolicy(null, Duration.ofDays(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
