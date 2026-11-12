package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.notification.domain.NotificationChannel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationRecipientsTest {

  private static final UUID TENANT = UUID.randomUUID();

  @ParameterizedTest
  @ValueSource(strings = {"a@example.com", "first.last+tag@sub.example.co"})
  void acceptsValidEmailAddresses(String recipient) {
    assertThatCode(
            () -> NotificationRecipients.validate(TENANT, recipient, NotificationChannel.EMAIL))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"a@example", "a@", "@example.com", "not-an-email", "a@example.c"})
  void rejectsMalformedEmailAddresses(String recipient) {
    assertThatThrownBy(
            () -> NotificationRecipients.validate(TENANT, recipient, NotificationChannel.EMAIL))
        .isInstanceOf(NotificationDeliveryException.class)
        .satisfies(
            ex -> {
              NotificationDeliveryException failure = (NotificationDeliveryException) ex;
              org.assertj.core.api.Assertions.assertThat(failure.getReason())
                  .isEqualTo("RECIPIENT_INVALID");
              org.assertj.core.api.Assertions.assertThat(failure.isPermanent()).isTrue();
            });
  }

  @Test
  void acceptsAnE164SmsNumber() {
    assertThatCode(
            () -> NotificationRecipients.validate(TENANT, "+905551234567", NotificationChannel.SMS))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnSmsNumberWithoutACountryCode() {
    assertThatThrownBy(
            () -> NotificationRecipients.validate(TENANT, "05551234567", NotificationChannel.SMS))
        .isInstanceOf(NotificationDeliveryException.class);
  }

  @Test
  void acceptsAPushDeviceToken() {
    assertThatCode(
            () ->
                NotificationRecipients.validate(
                    TENANT, "cJk3m9-abcdefghijklmnop_1234567890", NotificationChannel.PUSH))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAShortPushToken() {
    assertThatThrownBy(
            () -> NotificationRecipients.validate(TENANT, "short", NotificationChannel.PUSH))
        .isInstanceOf(NotificationDeliveryException.class);
  }

  @Test
  void acceptsWebAndWebhookRecipientsVerbatim() {
    assertThatCode(() -> NotificationRecipients.validate(TENANT, "user-1", NotificationChannel.WEB))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> NotificationRecipients.validate(TENANT, "whatever", NotificationChannel.WEBHOOK))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAMessageWithoutATenant() {
    assertThatThrownBy(
            () -> NotificationRecipients.validate(null, "a@example.com", NotificationChannel.EMAIL))
        .isInstanceOf(NotificationDeliveryException.class)
        .hasMessageContaining("no tenant");
  }

  @Test
  void rejectsAnEmptyRecipient() {
    assertThatThrownBy(
            () -> NotificationRecipients.validate(TENANT, "  ", NotificationChannel.EMAIL))
        .isInstanceOf(NotificationDeliveryException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> NotificationRecipients.validate(TENANT, null, NotificationChannel.SMS))
        .isInstanceOf(NotificationDeliveryException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void validatesTheWholeMessage() {
    NotificationMessage message =
        NotificationMessage.of(TENANT, "a@example.com", NotificationChannel.EMAIL, "s", "b");
    assertThatCode(() -> NotificationRecipients.validate(message)).doesNotThrowAnyException();
  }
}
