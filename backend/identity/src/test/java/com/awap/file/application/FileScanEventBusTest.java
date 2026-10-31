package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.file.domain.FileScanStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileScanEventBusTest {

  private final FileScanEventBus bus = new FileScanEventBus(Runnable::run);

  @Test
  void tenantScopedSubscribersNeverSeeAnotherTenantsEvents() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    List<FileScanStatusEvent> received = new ArrayList<>();
    try (var ignored = bus.subscribe(tenantA, null, received::add)) {
      bus.publish(event(tenantB, UUID.randomUUID(), FileScanStatus.SCANNING));
      bus.publish(event(tenantA, UUID.randomUUID(), FileScanStatus.CLEAN));
    }

    assertThat(received).hasSize(1);
    assertThat(received.get(0).tenantId()).isEqualTo(tenantA);
    assertThat(received.get(0).status()).isEqualTo(FileScanStatus.CLEAN);
  }

  @Test
  void perFileSubscriptionIgnoresOtherFiles() {
    UUID tenant = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    List<FileScanStatusEvent> received = new ArrayList<>();
    try (var ignored = bus.subscribe(tenant, target, received::add)) {
      bus.publish(event(tenant, UUID.randomUUID(), FileScanStatus.SCANNING));
      bus.publish(event(tenant, target, FileScanStatus.SCANNING));
      bus.publish(event(tenant, target, FileScanStatus.PROMOTED));
    }

    assertThat(received)
        .extracting(FileScanStatusEvent::status)
        .containsExactly(FileScanStatus.SCANNING, FileScanStatus.PROMOTED);
  }

  @Test
  void closedSubscriptionStopsDeliveryAndIsRemovedFromTheCount() {
    UUID tenant = UUID.randomUUID();
    List<FileScanStatusEvent> received = new ArrayList<>();
    var subscription = bus.subscribe(tenant, null, received::add);
    assertThat(bus.subscriberCount(tenant)).isEqualTo(1);

    subscription.close();
    subscription.close();
    bus.publish(event(tenant, UUID.randomUUID(), FileScanStatus.INFECTED));

    assertThat(received).isEmpty();
    assertThat(bus.subscriberCount(tenant)).isZero();
  }

  @Test
  void oneFailingListenerDoesNotStarveTheOthers() {
    UUID tenant = UUID.randomUUID();
    List<FileScanStatusEvent> received = new ArrayList<>();
    try (var failing =
            bus.subscribe(
                tenant,
                null,
                event -> {
                  throw new IllegalStateException("slow client");
                });
        var working = bus.subscribe(tenant, null, received::add)) {
      bus.publish(event(tenant, UUID.randomUUID(), FileScanStatus.SCAN_FAILED));
    }

    assertThat(received).hasSize(1);
    assertThat(received.get(0).status()).isEqualTo(FileScanStatus.SCAN_FAILED);
  }

  @Test
  void rejectsNullTenantAndListener() {
    UUID tenant = UUID.randomUUID();
    assertThatThrownBy(() -> bus.subscribe(null, null, event -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
    assertThatThrownBy(() -> bus.subscribe(tenant, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("listener");
  }

  @Test
  void nullEventsAndUnknownTenantsAreIgnored() {
    bus.publish(null);
    bus.publish(event(UUID.randomUUID(), UUID.randomUUID(), FileScanStatus.CLEAN));

    assertThat(bus.subscriberCount(UUID.randomUUID())).isZero();
  }

  @Test
  void rejectedDispatchIsDroppedWithoutBreakingThePublisher() {
    FileScanEventBus rejecting =
        new FileScanEventBus(
            command -> {
              throw new java.util.concurrent.RejectedExecutionException("queue full");
            });
    UUID tenant = UUID.randomUUID();
    try (var ignored = rejecting.subscribe(tenant, null, event -> {})) {
      rejecting.publish(event(tenant, UUID.randomUUID(), FileScanStatus.INFECTED));
    }
  }

  @Test
  void closeAllDropsEverySubscription() {
    UUID tenant = UUID.randomUUID();
    bus.subscribe(tenant, null, event -> {});
    assertThat(bus.subscriberCount(tenant)).isEqualTo(1);

    bus.closeAll();

    assertThat(bus.subscriberCount(tenant)).isZero();
  }

  private FileScanStatusEvent event(UUID tenant, UUID file, FileScanStatus status) {
    return new FileScanStatusEvent(
        tenant, file, "report.pdf", status, "detail", Instant.parse("2026-01-01T00:00:00Z"));
  }
}
