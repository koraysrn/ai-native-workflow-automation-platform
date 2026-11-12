package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.notification.application.NotificationDeliveryStateStore;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lease-guarded state transitions delegate to the conditional JPQL updates (issue #204). */
class JpaNotificationDeliveryStateStoreTest {

  private static final UUID ID = UUID.randomUUID();
  private static final UUID TOKEN = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-07-01T08:00:00Z");

  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final NotificationDeliveryStateStore store =
      new JpaNotificationDeliveryStateStore(repository);

  @Test
  void reportsTheLeaseAsCurrentOnlyWhileTheRowIsRetriable() {
    when(repository.existsByIdAndLeaseTokenAndStatusIn(
            ID, TOKEN, List.of(NotificationDelivery.PENDING, NotificationDelivery.RETRYING)))
        .thenReturn(true);

    assertThat(store.isLeaseCurrent(ID, TOKEN)).isTrue();
  }

  @Test
  void markSentStoresTheProviderMessageId() {
    when(repository.applySent(ID, TOKEN, 2, NOW, "<abc@awap.test>")).thenReturn(1);

    assertThat(store.markSent(ID, TOKEN, 2, NOW, "<abc@awap.test>")).isTrue();
  }

  @Test
  void aLostLeaseMakesEveryWriteFail() {
    when(repository.applySent(ID, TOKEN, 2, NOW, null)).thenReturn(0);
    when(repository.applyRetrying(ID, TOKEN, 2, "err", NOW, NOW)).thenReturn(0);
    when(repository.applyFailed(ID, TOKEN, 2, "err", NOW)).thenReturn(0);

    assertThat(store.markSent(ID, TOKEN, 2, NOW, null)).isFalse();
    assertThat(store.markRetrying(ID, TOKEN, 2, "err", NOW, NOW)).isFalse();
    assertThat(store.markFailedPermanently(ID, TOKEN, 2, "err", NOW)).isFalse();
  }

  @Test
  void aWonLeaseMakesEveryWriteSucceed() {
    when(repository.applyRetrying(ID, TOKEN, 2, "err", NOW, NOW)).thenReturn(1);
    when(repository.applyFailed(ID, TOKEN, 3, "err", NOW)).thenReturn(1);

    assertThat(store.markRetrying(ID, TOKEN, 2, "err", NOW, NOW)).isTrue();
    assertThat(store.markFailedPermanently(ID, TOKEN, 3, "err", NOW)).isTrue();
  }
}
