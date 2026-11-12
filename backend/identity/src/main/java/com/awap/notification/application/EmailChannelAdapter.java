package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * SMTP e-mail transport (issue #204).
 *
 * <p>The first attempt is bounded by the configured connect/read/write timeouts so a slow relay
 * cannot block the Kafka consumer; anything that is not a permanent provider error is thrown as a
 * retryable {@link NotificationDeliveryException} and finished by the retry scheduler.
 *
 * <p>Credentials are taken from the bound provider settings (env/Vault) and never logged. The
 * returned provider message id is the RFC 5322 {@code Message-ID} of the sent message, which is
 * what the SMTP server and the receiving mailbox record, so a bounce report can be correlated with
 * the delivery row.
 */
@Component
public class EmailChannelAdapter implements NotificationChannelAdapter {

  static final String DEFAULT_FROM = "no-reply@awap.local";

  private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

  private final NotificationProviderProperties.Email settings;
  private final JavaMailSender sender;

  @Autowired
  public EmailChannelAdapter(NotificationProviderProperties properties) {
    this(properties.getEmail());
  }

  EmailChannelAdapter(NotificationProviderProperties.Email settings) {
    this(settings, settings.isEnabled() ? mailSender(settings) : null);
  }

  EmailChannelAdapter(NotificationProviderProperties.Email settings, JavaMailSender sender) {
    this.settings = settings;
    this.sender = sender;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public String dispatch(NotificationMessage message) {
    if (!settings.isEnabled()) {
      log.info(
          "EMAIL transport disabled -> {} subject='{}' bytes={}",
          message.recipient(),
          message.subject(),
          message.body().length());
      return null;
    }
    String providerMessageId = "<" + UUID.randomUUID() + "@" + domain(settings.getFrom()) + ">";
    try {
      MimeMessage mime = sender.createMimeMessage();
      mime.setFrom(new InternetAddress(settings.getFrom()));
      mime.setRecipients(
          Message.RecipientType.TO, InternetAddress.parse(message.recipient(), true));
      mime.setSubject(message.subject(), "UTF-8");
      mime.setText(message.body(), "UTF-8");
      mime.setHeader("Message-ID", providerMessageId);
      sender.send(mime);
      return providerMessageId;
    } catch (MailException | MessagingException ex) {
      throw classify(ex);
    }
  }

  /**
   * Builds the Jakarta Mail sender from the bound settings. Fails fast when the transport is
   * enabled without a host or with an unusable {@code from} address, so a misconfiguration is
   * visible at startup instead of surfacing as a silent per-message failure.
   */
  static JavaMailSenderImpl mailSender(NotificationProviderProperties.Email settings) {
    if (isBlank(settings.getHost())) {
      throw new IllegalStateException(
          "awap.notification.providers.email.enabled=true requires"
              + " awap.notification.providers.email.host (supply it from the environment or Vault).");
    }
    if (settings.getFrom() == null) {
      throw new IllegalStateException(
          "awap.notification.providers.email.from must be configured when the transport is enabled.");
    }
    try {
      new InternetAddress(settings.getFrom());
    } catch (AddressException ex) {
      throw new IllegalStateException(
          "awap.notification.providers.email.from is not a valid e-mail address.", ex);
    }
    JavaMailSenderImpl impl = new JavaMailSenderImpl();
    impl.setHost(settings.getHost());
    impl.setPort(settings.getPort());
    impl.setDefaultEncoding("UTF-8");
    Properties mailProperties = impl.getJavaMailProperties();
    String timeout = Long.toString(Math.max(settings.getTimeoutMs(), 1L));
    mailProperties.put("mail.smtp.connectiontimeout", timeout);
    mailProperties.put("mail.smtp.timeout", timeout);
    mailProperties.put("mail.smtp.writetimeout", timeout);
    if (!isBlank(settings.getUsername())) {
      impl.setUsername(settings.getUsername());
      impl.setPassword(settings.getPassword());
      mailProperties.put("mail.smtp.auth", "true");
    }
    if (settings.isStarttls()) {
      mailProperties.put("mail.smtp.starttls.enable", "true");
    }
    return impl;
  }

  /**
   * Maps a Jakarta Mail failure to the retry classification. Authentication failures, hard bounces
   * (5xx recipient rejection) and invalid addresses are permanent; temporary SMTP replies (4xx) and
   * IO timeouts stay retryable.
   */
  static NotificationDeliveryException classify(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof AuthenticationFailedException) {
        return NotificationDeliveryException.permanent(
            "SMTP_AUTH_FAILED", "SMTP authentication failed: " + text(current));
      }
      if (current instanceof SMTPSendFailedException sendFailed) {
        return sendFailed.getReturnCode() >= 500
            ? NotificationDeliveryException.blockedRecipient("SMTP_BOUNCE", text(current))
            : NotificationDeliveryException.retryable("SMTP_TEMPORARY", text(current));
      }
      if (current instanceof SendFailedException) {
        return NotificationDeliveryException.blockedRecipient("SMTP_BOUNCE", text(current));
      }
      if (current instanceof AddressException) {
        return NotificationDeliveryException.permanent("SMTP_ADDRESS_INVALID", text(current));
      }
    }
    return NotificationDeliveryException.retryable("SMTP_IO", text(failure));
  }

  static String domain(String from) {
    String address = from == null || from.isBlank() ? DEFAULT_FROM : from;
    return address.substring(address.lastIndexOf('@') + 1);
  }

  private static String text(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
