package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationSuppressionTest {

  @Test
  void recordsTheBlockedRecipientAndReason() {
    UUID tenantId = UUID.randomUUID();

    NotificationSuppression suppression =
        new NotificationSuppression(
            tenantId, NotificationChannel.SMS, "+905551234567", "SMS_BOUNCE");

    assertThat(suppression.getId()).isNotNull();
    assertThat(suppression.getTenantId()).isEqualTo(tenantId);
    assertThat(suppression.getChannel()).isEqualTo(NotificationChannel.SMS);
    assertThat(suppression.getRecipient()).isEqualTo("+905551234567");
    assertThat(suppression.getReason()).isEqualTo("SMS_BOUNCE");
    assertThat(suppression.getCreatedAt()).isNotNull();
  }

  @Test
  void thePersistenceConstructorLeavesTheRowEmpty() {
    NotificationSuppression empty = new NotificationSuppression();

    assertThat(empty.getId()).isNull();
    assertThat(empty.getTenantId()).isNull();
    assertThat(empty.getChannel()).isNull();
    assertThat(empty.getRecipient()).isNull();
    assertThat(empty.getReason()).isNull();
    assertThat(empty.getCreatedAt()).isNull();
  }
}
