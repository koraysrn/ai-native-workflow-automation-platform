package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationProviderPropertiesTest {

  @Test
  void everyTransportIsDisabledWithSafeDefaults() {
    NotificationProviderProperties properties = new NotificationProviderProperties();

    assertThat(properties.getEmail().isEnabled()).isFalse();
    assertThat(properties.getEmail().getPort()).isEqualTo(587);
    assertThat(properties.getEmail().getFrom()).isEqualTo("no-reply@awap.local");
    assertThat(properties.getEmail().isStarttls()).isTrue();
    assertThat(properties.getEmail().getTimeoutMs()).isEqualTo(5_000L);
    assertThat(properties.getSms().isEnabled()).isFalse();
    assertThat(properties.getSms().getSender()).isEqualTo("AWAP");
    assertThat(properties.getSms().getTimeoutMs()).isEqualTo(5_000L);
    assertThat(properties.getPush().isEnabled()).isFalse();
    assertThat(properties.getPush().getTimeoutMs()).isEqualTo(5_000L);
  }

  @Test
  void bindsEveryDocumentedSetting() {
    NotificationProviderProperties properties = new NotificationProviderProperties();
    NotificationProviderProperties.Email email = new NotificationProviderProperties.Email();
    email.setEnabled(true);
    email.setHost("smtp.example.com");
    email.setPort(2525);
    email.setUsername("user");
    email.setPassword("secret");
    email.setFrom("no-reply@example.com");
    email.setStarttls(false);
    email.setTimeoutMs(2_000L);
    properties.setEmail(email);

    NotificationProviderProperties.Sms sms = new NotificationProviderProperties.Sms();
    sms.setEnabled(true);
    sms.setEndpoint("https://sms.example.com/v1/messages");
    sms.setToken("sms-token");
    sms.setSender("EGS");
    sms.setTimeoutMs(1_500L);
    properties.setSms(sms);

    NotificationProviderProperties.Push push = new NotificationProviderProperties.Push();
    push.setEnabled(true);
    push.setEndpoint("https://fcm.example.com/v1/messages:send");
    push.setToken("push-token");
    push.setTimeoutMs(1_000L);
    properties.setPush(push);

    assertThat(properties.getEmail().getHost()).isEqualTo("smtp.example.com");
    assertThat(properties.getEmail().getPort()).isEqualTo(2525);
    assertThat(properties.getEmail().getUsername()).isEqualTo("user");
    assertThat(properties.getEmail().getPassword()).isEqualTo("secret");
    assertThat(properties.getEmail().getFrom()).isEqualTo("no-reply@example.com");
    assertThat(properties.getEmail().isStarttls()).isFalse();
    assertThat(properties.getEmail().getTimeoutMs()).isEqualTo(2_000L);
    assertThat(properties.getSms().getEndpoint()).isEqualTo("https://sms.example.com/v1/messages");
    assertThat(properties.getSms().getToken()).isEqualTo("sms-token");
    assertThat(properties.getSms().getSender()).isEqualTo("EGS");
    assertThat(properties.getSms().getTimeoutMs()).isEqualTo(1_500L);
    assertThat(properties.getPush().getEndpoint())
        .isEqualTo("https://fcm.example.com/v1/messages:send");
    assertThat(properties.getPush().getToken()).isEqualTo("push-token");
    assertThat(properties.getPush().getTimeoutMs()).isEqualTo(1_000L);
    assertThat(properties.getEmail().isEnabled()).isTrue();
    assertThat(properties.getSms().isEnabled()).isTrue();
    assertThat(properties.getPush().isEnabled()).isTrue();
  }

  @Test
  void nullSectionsFallBackToTheDisabledDefaults() {
    NotificationProviderProperties properties = new NotificationProviderProperties();
    properties.setEmail(null);
    properties.setSms(null);
    properties.setPush(null);

    assertThat(properties.getEmail().isEnabled()).isFalse();
    assertThat(properties.getSms().isEnabled()).isFalse();
    assertThat(properties.getPush().isEnabled()).isFalse();
  }
}
