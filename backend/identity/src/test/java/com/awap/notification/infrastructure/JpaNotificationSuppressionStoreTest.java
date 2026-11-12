package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.notification.application.NotificationSuppressionStore;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationSuppression;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

class JpaNotificationSuppressionStoreTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final String RECIPIENT = "a@example.com";

  private final NotificationSuppressionRepository repository =
      Mockito.mock(NotificationSuppressionRepository.class);
  private final NotificationSuppressionStore store =
      new JpaNotificationSuppressionStore(repository);

  @Test
  void reportsTheRecordedReason() {
    when(repository.findByTenantIdAndChannelAndRecipient(
            TENANT, NotificationChannel.EMAIL, RECIPIENT))
        .thenReturn(
            Optional.of(
                new NotificationSuppression(
                    TENANT, NotificationChannel.EMAIL, RECIPIENT, "SMTP_BOUNCE")));

    assertThat(store.suppressionReason(TENANT, NotificationChannel.EMAIL, RECIPIENT))
        .contains("SMTP_BOUNCE");
  }

  @Test
  void reportsEmptyForADeliverableRecipient() {
    when(repository.findByTenantIdAndChannelAndRecipient(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThat(store.suppressionReason(TENANT, NotificationChannel.SMS, "+905551234567")).isEmpty();
  }

  @Test
  void recordsANewSuppression() {
    when(repository.existsByTenantIdAndChannelAndRecipient(any(), any(), any())).thenReturn(false);

    store.suppress(TENANT, NotificationChannel.EMAIL, RECIPIENT, "SMTP_BOUNCE");

    ArgumentCaptor<NotificationSuppression> captor =
        ArgumentCaptor.forClass(NotificationSuppression.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
    assertThat(captor.getValue().getRecipient()).isEqualTo(RECIPIENT);
    assertThat(captor.getValue().getReason()).isEqualTo("SMTP_BOUNCE");
    assertThat(captor.getValue().getCreatedAt()).isNotNull();
  }

  @Test
  void anAlreadyRecordedSuppressionIsNotWrittenTwice() {
    when(repository.existsByTenantIdAndChannelAndRecipient(any(), any(), any())).thenReturn(true);

    store.suppress(TENANT, NotificationChannel.EMAIL, RECIPIENT, "SMTP_BOUNCE");

    verify(repository, never()).save(any());
  }

  @Test
  void aConcurrentInsertIsSwallowed() {
    when(repository.existsByTenantIdAndChannelAndRecipient(any(), any(), any())).thenReturn(false);
    when(repository.save(any()))
        .thenThrow(new DataIntegrityViolationException("uq_notification_suppressions"));

    store.suppress(TENANT, NotificationChannel.PUSH, "token-abcdefghijklmnop", "PUSH_BOUNCE");

    verify(repository).save(any());
  }
}
