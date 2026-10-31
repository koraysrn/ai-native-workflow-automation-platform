package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;

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

  private FileScanStatusEvent event(UUID tenant, UUID file, FileScanStatus status) {
    return new FileScanStatusEvent(
        tenant, file, "report.pdf", status, "detail", Instant.parse("2026-01-01T00:00:00Z"));
  }
}
