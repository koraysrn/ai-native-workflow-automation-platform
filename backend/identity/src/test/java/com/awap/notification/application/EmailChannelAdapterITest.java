package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.notification.domain.NotificationChannel;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Proves the SMTP wire payload: real delivery into GreenMail (issue #204). */
class EmailChannelAdapterITest {

  @RegisterExtension
  static final GreenMailExtension GREEN_MAIL =
      new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(false);

  @Test
  void deliversTheRenderedSubjectAndBodyOverSmtpAndReturnsTheMessageId() throws Exception {
    NotificationProviderProperties.Email settings = new NotificationProviderProperties.Email();
    settings.setEnabled(true);
    settings.setHost("127.0.0.1");
    settings.setPort(GREEN_MAIL.getSmtp().getPort());
    settings.setFrom("no-reply@awap.test");
    settings.setStarttls(false);
    settings.setTimeoutMs(5_000L);
    EmailChannelAdapter adapter = new EmailChannelAdapter(settings);

    String providerMessageId =
        adapter.dispatch(
            NotificationMessage.of(
                UUID.randomUUID(),
                "assignee@example.com",
                NotificationChannel.EMAIL,
                "Task assigned",
                "Hello Ada"));

    assertThat(providerMessageId).isNotBlank();
    assertThat(providerMessageId).startsWith("<").endsWith("@awap.test>");
    MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
    assertThat(received).hasSize(1);
    assertThat(received[0].getSubject()).isEqualTo("Task assigned");
    assertThat(received[0].getContent().toString()).contains("Hello Ada");
    assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("assignee@example.com");
    assertThat(received[0].getHeader("Message-ID")[0]).isEqualTo(providerMessageId);
  }
}
