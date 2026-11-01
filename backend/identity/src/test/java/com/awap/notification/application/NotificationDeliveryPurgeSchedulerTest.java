package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.notification.domain.NotificationRetentionPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDeliveryPurgeSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T03:30:00Z");

  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NotificationDeliveryPurgeScheduler scheduler =
      new NotificationDeliveryPurgeScheduler(
          repository,
          new NotificationRetentionPolicy(Duration.ofDays(30), Duration.ofDays(180)),
          Clock.fixed(NOW, ZoneOffset.UTC),
          registry);

  @Test
  void purgesReadAndUnreadDeliveriesWithIndependentCutoffs() {
    when(repository.purgeReadBefore(any())).thenReturn(3);
    when(repository.purgeUnreadBefore(any())).thenReturn(1);

    NotificationDeliveryPurgeScheduler.PurgeResult result = scheduler.purgeExpired();

    ArgumentCaptor<OffsetDateTime> readCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    ArgumentCaptor<OffsetDateTime> unreadCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repository).purgeReadBefore(readCutoff.capture());
    verify(repository).purgeUnreadBefore(unreadCutoff.capture());

    assertThat(readCutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(unreadCutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofDays(180)));
    assertThat(readCutoff.getValue().toInstant()).isAfter(unreadCutoff.getValue().toInstant());
    assertThat(result.readNotificationsPurged()).isEqualTo(3);
    assertThat(result.unreadNotificationsPurged()).isEqualTo(1);
    assertThat(purgedCount()).isEqualTo(4.0);
  }

  @Test
  void reportsZeroWhenNothingExpires() {
    when(repository.purgeReadBefore(any())).thenReturn(0);
    when(repository.purgeUnreadBefore(any())).thenReturn(0);

    NotificationDeliveryPurgeScheduler.PurgeResult result = scheduler.purgeExpired();

    assertThat(result).isEqualTo(new NotificationDeliveryPurgeScheduler.PurgeResult(0, 0));
    assertThat(purgedCount()).isZero();
    assertThat(registry.find(NotificationDeliveryPurgeScheduler.PURGED_METRIC).counter()).isNull();
  }

  @Test
  void incrementsThePurgeCounterForAReadOnlyRun() {
    when(repository.purgeReadBefore(any())).thenReturn(2);
    when(repository.purgeUnreadBefore(any())).thenReturn(0);

    scheduler.purgeExpired();

    assertThat(purgedCount()).isEqualTo(2.0);
  }

  @Test
  void incrementsThePurgeCounterForAnUnreadOnlyRun() {
    when(repository.purgeReadBefore(any())).thenReturn(0);
    when(repository.purgeUnreadBefore(any())).thenReturn(5);

    scheduler.purgeExpired();

    assertThat(purgedCount()).isEqualTo(5.0);
  }

  @Test
  void shortReadWindowNeverShortensTheUnreadWindow() {
    NotificationDeliveryPurgeScheduler shortRead =
        new NotificationDeliveryPurgeScheduler(
            repository,
            new NotificationRetentionPolicy(Duration.ofHours(1), Duration.ofDays(365)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            registry);
    when(repository.purgeReadBefore(any())).thenReturn(0);
    when(repository.purgeUnreadBefore(any())).thenReturn(0);

    shortRead.purgeExpired();

    ArgumentCaptor<OffsetDateTime> readCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    ArgumentCaptor<OffsetDateTime> unreadCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repository).purgeReadBefore(readCutoff.capture());
    verify(repository).purgeUnreadBefore(unreadCutoff.capture());
    assertThat(readCutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    assertThat(unreadCutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofDays(365)));
  }

  private double purgedCount() {
    var counter = registry.find(NotificationDeliveryPurgeScheduler.PURGED_METRIC).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
