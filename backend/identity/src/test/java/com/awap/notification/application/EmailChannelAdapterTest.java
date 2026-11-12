package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.awap.notification.domain.NotificationChannel;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Unit-level checks of the SMTP configuration and error classification (issue #204). */
class EmailChannelAdapterTest {

  private static final UUID TENANT = UUID.randomUUID();

  private static NotificationMessage message() {
    return NotificationMessage.of(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "Welcome", "Hi");
  }

  private static NotificationProviderProperties.Email enabledSettings() {
    NotificationProviderProperties.Email settings = new NotificationProviderProperties.Email();
    settings.setEnabled(true);
    settings.setHost("smtp.example.com");
    settings.setPort(2525);
    settings.setFrom("no-reply@example.com");
    settings.setStarttls(false);
    settings.setTimeoutMs(1_500L);
    return settings;
  }

  private static JavaMailSender sender() {
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage())
        .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    return sender;
  }

  @Test
  void disabledTransportIgnoresTheMessageAndReturnsNoId() {
    EmailChannelAdapter adapter =
        new EmailChannelAdapter(new NotificationProviderProperties.Email(), null);

    assertThat(adapter.dispatch(message())).isNull();
    assertThat(adapter.channel()).isEqualTo(NotificationChannel.EMAIL);
  }

  @Test
  void enabledTransportWithoutHostFailsFast() {
    NotificationProviderProperties.Email settings = enabledSettings();
    settings.setHost(" ");

    assertThatThrownBy(() -> new EmailChannelAdapter(settings))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("email.host");
  }

  @Test
  void enabledTransportWithoutFromFailsFast() {
    NotificationProviderProperties.Email settings = enabledSettings();
    settings.setFrom(null);

    assertThatThrownBy(() -> new EmailChannelAdapter(settings))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("email.from");
  }

  @Test
  void enabledTransportWithAnInvalidFromFailsFast() {
    NotificationProviderProperties.Email settings = enabledSettings();
    settings.setFrom("not a valid@example.com");

    assertThatThrownBy(() -> new EmailChannelAdapter(settings))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid e-mail address");
  }

  @Test
  void mailSenderAppliesBoundedTimeoutsAndCredentials() {
    NotificationProviderProperties.Email settings = enabledSettings();
    settings.setUsername("awap");
    settings.setPassword("secret");
    settings.setStarttls(true);

    JavaMailSenderImpl sender = EmailChannelAdapter.mailSender(settings);

    assertThat(sender.getHost()).isEqualTo("smtp.example.com");
    assertThat(sender.getPort()).isEqualTo(2525);
    assertThat(sender.getUsername()).isEqualTo("awap");
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"))
        .isEqualTo("1500");
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.timeout")).isEqualTo("1500");
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout"))
        .isEqualTo("1500");
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"))
        .isEqualTo("true");
  }

  @Test
  void mailSenderSkipsAuthAndStarttlsWhenNotConfigured() {
    JavaMailSenderImpl sender = EmailChannelAdapter.mailSender(enabledSettings());

    assertThat(sender.getUsername()).isNull();
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isNull();
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  void authenticationFailureIsPermanent() {
    JavaMailSender sender = sender();
    doThrow(new MailAuthenticationException(new AuthenticationFailedException("535 denied")))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMTP_AUTH_FAILED");
              assertThat(ex.isPermanent()).isTrue();
              assertThat(ex.isRecipientBlocked()).isFalse();
            });
  }

  @Test
  void aFiveHundredReplyIsABounce() {
    JavaMailSender sender = sender();
    doThrow(
            new MailSendException(
                "rejected",
                new SMTPSendFailedException(
                    "RCPT TO", 550, "mailbox unavailable", null, null, null, null)))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMTP_BOUNCE");
              assertThat(ex.isPermanent()).isTrue();
              assertThat(ex.isRecipientBlocked()).isTrue();
            });
  }

  @Test
  void aFourHundredReplyStaysRetryable() {
    JavaMailSender sender = sender();
    doThrow(
            new MailSendException(
                "deferred",
                new SMTPSendFailedException(
                    "RCPT TO", 450, "try again later", null, null, null, null)))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMTP_TEMPORARY");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void aGenericSendFailureIsTreatedAsABounce() {
    JavaMailSender sender = sender();
    doThrow(new MailSendException("failed", new SendFailedException("invalid addresses")))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> assertThat(ex.isRecipientBlocked()).isTrue());
  }

  @Test
  void anAddressFailureIsPermanent() {
    JavaMailSender sender = sender();
    doThrow(new MailSendException("failed", new AddressException("bad address")))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMTP_ADDRESS_INVALID");
              assertThat(ex.isPermanent()).isTrue();
            });
  }

  @Test
  void aTimeoutIsRetryable() {
    JavaMailSender sender = sender();
    doThrow(new MailSendException("io", new SocketTimeoutException()))
        .when(sender)
        .send(any(MimeMessage.class));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMTP_IO");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void aMessagingFailureWhileBuildingTheMessageIsClassifiedToo() {
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage())
        .thenThrow(new MailSendException("cannot create", new MessagingException("boom")));
    EmailChannelAdapter adapter = new EmailChannelAdapter(enabledSettings(), sender);

    assertThatThrownBy(() -> adapter.dispatch(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class, ex -> assertThat(ex.isPermanent()).isFalse());
  }

  @Test
  void theProviderMessageIdUsesTheConfiguredDomain() {
    assertThat(EmailChannelAdapter.domain("no-reply@example.com")).isEqualTo("example.com");
    assertThat(EmailChannelAdapter.domain(null)).isEqualTo("awap.local");
    assertThat(EmailChannelAdapter.domain(" ")).isEqualTo("awap.local");
  }

  @Test
  void disabledTransportNeverTouchesTheProvider() {
    JavaMailSender sender = sender();
    EmailChannelAdapter adapter =
        new EmailChannelAdapter(new NotificationProviderProperties.Email(), sender);

    assertThat(adapter.dispatch(message())).isNull();
    verifyNoInteractions(sender);
  }
}
