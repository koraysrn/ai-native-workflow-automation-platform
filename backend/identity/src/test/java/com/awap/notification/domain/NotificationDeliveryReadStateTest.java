package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryReadStateTest {

  private static final Instant FIRST_READ = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant SECOND_READ = Instant.parse("2026-01-03T10:00:00Z");

  private NotificationDelivery delivery() {
    return new NotificationDelivery(
        UUID.randomUUID(), "dedupe", NotificationChannel.WEB, "user@example.com", "welcome");
  }

  @Test
  void newDeliveryIsUnread() {
    NotificationDelivery delivery = delivery();

    assertThat(delivery.isRead()).isFalse();
    assertThat(delivery.readAt()).isEmpty();
  }

  @Test
  void markReadStoresTimestampAndUpdatesAuditColumn() {
    NotificationDelivery delivery = delivery();

    boolean changed = delivery.markRead(FIRST_READ);

    assertThat(changed).isTrue();
    assertThat(delivery.isRead()).isTrue();
    assertThat(delivery.readAt()).contains(FIRST_READ);
    assertThat(delivery.getUpdatedAt()).isEqualTo(FIRST_READ);
  }

  @Test
  void repeatedMarkReadKeepsFirstTimestamp() {
    NotificationDelivery delivery = delivery();
    delivery.markRead(FIRST_READ);

    boolean changed = delivery.markRead(SECOND_READ);

    assertThat(changed).isFalse();
    assertThat(delivery.readAt()).contains(FIRST_READ);
    assertThat(delivery.getUpdatedAt()).isEqualTo(FIRST_READ);
  }

  @Test
  void markUnreadRestoresUnreadState() {
    NotificationDelivery delivery = delivery();
    delivery.markRead(FIRST_READ);

    boolean changed = delivery.markUnread(SECOND_READ);

    assertThat(changed).isTrue();
    assertThat(delivery.isRead()).isFalse();
    assertThat(delivery.readAt()).isEmpty();
    assertThat(delivery.getUpdatedAt()).isEqualTo(SECOND_READ);
  }

  @Test
  void repeatedMarkUnreadIsNoOp() {
    NotificationDelivery delivery = delivery();
    Instant untouched = delivery.getUpdatedAt();

    boolean changed = delivery.markUnread(SECOND_READ);

    assertThat(changed).isFalse();
    assertThat(delivery.isRead()).isFalse();
    assertThat(delivery.getUpdatedAt()).isEqualTo(untouched);
  }

  @Test
  void deliveryStartsPendingWithNoAttempts() {
    NotificationDelivery delivery = delivery();

    assertThat(delivery.getStatus()).isEqualTo("PENDING");
    assertThat(delivery.getAttempts()).isZero();
    assertThat(delivery.getVersion()).isZero();
  }

  @Test
  void nullLocaleFallsBackToEnglish() {
    NotificationDelivery delivery =
        new NotificationDelivery(
            UUID.randomUUID(),
            "dedupe",
            NotificationChannel.WEB,
            "user@example.com",
            "welcome",
            null,
            null);

    assertThat(delivery.getLocale()).isEqualTo("en");
  }

  @Test
  void blankLocaleFallsBackToEnglish() {
    NotificationDelivery delivery =
        new NotificationDelivery(
            UUID.randomUUID(),
            "dedupe",
            NotificationChannel.WEB,
            "user@example.com",
            "welcome",
            "   ",
            null);

    assertThat(delivery.getLocale()).isEqualTo("en");
  }
}
