package com.awap.notification.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationControllerMappingTest {

  @Test
  void exposesTheRetryScheduleAttemptsAndLastError() {
    Instant now = Instant.parse("2026-08-01T12:00:00Z");
    NotificationDelivery delivery =
        new NotificationDelivery(
            UUID.randomUUID(),
            "dedupe-1",
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            "tr",
            "{\"name\":\"Ada\"}");
    delivery.scheduleFirstAttempt(now.plusSeconds(60));
    delivery.recordAttempt(now);
    delivery.markRetrying("provider 500", now.plusSeconds(60), now);

    NotificationController.NotificationDeliveryResponse response =
        NotificationController.toResponse(delivery);

    assertThat(response.nextAttemptAt()).isEqualTo(now.plusSeconds(60));
    assertThat(response.attempts()).isEqualTo(1);
    assertThat(response.lastError()).isEqualTo("provider 500");
    assertThat(response.status()).isEqualTo("RETRYING");
  }

  @Test
  void sentDeliveryHasNoNextAttempt() {
    Instant now = Instant.parse("2026-08-01T12:00:00Z");
    NotificationDelivery delivery =
        new NotificationDelivery(
            UUID.randomUUID(), "dedupe-2", NotificationChannel.EMAIL, "a@example.com", "welcome");
    delivery.recordAttempt(now);
    delivery.markSent(now);

    NotificationController.NotificationDeliveryResponse response =
        NotificationController.toResponse(delivery);

    assertThat(response.nextAttemptAt()).isNull();
    assertThat(response.attempts()).isEqualTo(1);
    assertThat(response.status()).isEqualTo("SENT");
  }
}
