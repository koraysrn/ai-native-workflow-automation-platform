package com.awap.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit coverage for the tenant-scoped read-state adapter (issue #157). */
class JpaNotificationReadStoreTest {

  private static final Instant NOW = Instant.parse("2026-06-01T08:00:00Z");

  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final JpaNotificationReadStore store = new JpaNotificationReadStore(repository);

  @Test
  void findAllForwardsTheUnreadPredicateToTheIndexedQuery() {
    UUID tenant = UUID.randomUUID();
    List<NotificationDelivery> unread = List.of(delivery(tenant));
    when(repository.findByTenantIdAndReadAtIsNullOrderByCreatedAtDesc(tenant)).thenReturn(unread);

    assertThat(store.findAll(tenant, true)).isEqualTo(unread);
    verify(repository).findByTenantIdAndReadAtIsNullOrderByCreatedAtDesc(tenant);
  }

  @Test
  void findAllUsesTheFullTenantInboxWhenUnreadFilterIsOff() {
    UUID tenant = UUID.randomUUID();
    List<NotificationDelivery> all = List.of(delivery(tenant));
    when(repository.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(all);

    assertThat(store.findAll(tenant, false)).isEqualTo(all);
    verify(repository).findByTenantIdOrderByCreatedAtDesc(tenant);
  }

  @Test
  void countUnreadIsScopedToTheTenant() {
    UUID tenant = UUID.randomUUID();
    when(repository.countByTenantIdAndReadAtIsNull(tenant)).thenReturn(4L);

    assertThat(store.countUnread(tenant)).isEqualTo(4L);
  }

  @Test
  void findByIdReturnsADeliveryOwnedByTheTenant() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery delivery = delivery(tenant);
    when(repository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

    assertThat(store.findById(tenant, delivery.getId())).contains(delivery);
  }

  @Test
  void findByIdHidesADeliveryOwnedByAnotherTenant() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery foreign = delivery(UUID.randomUUID());
    when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThat(store.findById(tenant, foreign.getId())).isEmpty();
  }

  @Test
  void findByIdIsEmptyWhenTheDeliveryNoLongerExists() {
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThat(store.findById(tenant, id)).isEmpty();
  }

  @Test
  void markReadReportsWhetherARowChanged() {
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(repository.markRead(id, tenant, NOW)).thenReturn(1);
    when(repository.markUnread(id, tenant, NOW)).thenReturn(0);

    assertThat(store.markRead(tenant, id, NOW)).isTrue();
    assertThat(store.markUnread(tenant, id, NOW)).isFalse();
  }

  @Test
  void markUnreadReportsWhetherARowChanged() {
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(repository.markUnread(id, tenant, NOW)).thenReturn(1);
    when(repository.markRead(id, tenant, NOW)).thenReturn(0);

    assertThat(store.markUnread(tenant, id, NOW)).isTrue();
    assertThat(store.markRead(tenant, id, NOW)).isFalse();
  }

  private static NotificationDelivery delivery(UUID tenant) {
    return new NotificationDelivery(
        tenant,
        "dedupe-" + UUID.randomUUID(),
        NotificationChannel.WEB,
        "user@example.com",
        "welcome");
  }
}
