package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotificationReadServiceTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final InMemoryReadStore store = new InMemoryReadStore();
  private final MutableClock clock = new MutableClock();
  private final NotificationReadService service =
      new NotificationReadService(store, registry, clock);

  @Test
  void markingReadDecrementsTheUnreadCountAndPublishesMetrics() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery first = store.add(delivery(tenant, "first@example.com"));
    store.add(delivery(tenant, "second@example.com"));

    assertThat(service.unreadCount(tenant)).isEqualTo(2);

    NotificationDelivery read = service.markRead(tenant, first.getId());

    assertThat(read.isRead()).isTrue();
    assertThat(read.readAt()).contains(clock.instant());
    assertThat(service.unreadCount(tenant)).isEqualTo(1);
    assertThat(gaugeValue(tenant)).isEqualTo(1.0);
    assertThat(
            registry
                .get(NotificationReadService.READ_LATENCY_TIMER)
                .tags("operation", "read", "result", "changed")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(NotificationReadService.TRANSITION_COUNTER)
                .tags("operation", "read", "result", "changed")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void repeatedMarkReadIsIdempotent() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery delivery = store.add(delivery(tenant, "a@example.com"));

    service.markRead(tenant, delivery.getId());
    Instant firstReadAt = delivery.readAt().orElseThrow();
    clock.advance(Duration.ofMinutes(5));
    NotificationDelivery second = service.markRead(tenant, delivery.getId());

    assertThat(second.readAt()).contains(firstReadAt);
    assertThat(store.readTransitions()).isEqualTo(1);
    assertThat(service.unreadCount(tenant)).isZero();
    assertThat(
            registry
                .get(NotificationReadService.TRANSITION_COUNTER)
                .tags("operation", "read", "result", "unchanged")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void markingUnreadRestoresTheCount() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery first = store.add(delivery(tenant, "first@example.com"));
    NotificationDelivery second = store.add(delivery(tenant, "second@example.com"));
    service.markRead(tenant, first.getId());
    service.markRead(tenant, second.getId());
    assertThat(service.unreadCount(tenant)).isZero();

    NotificationDelivery unread = service.markUnread(tenant, first.getId());

    assertThat(unread.isRead()).isFalse();
    assertThat(service.unreadCount(tenant)).isEqualTo(1);
    assertThat(gaugeValue(tenant)).isEqualTo(1.0);
  }

  @Test
  void repeatingMarkUnreadIsIdempotent() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery delivery = store.add(delivery(tenant, "a@example.com"));

    service.markUnread(tenant, delivery.getId());

    assertThat(store.unreadTransitions()).isZero();
    assertThat(
            registry
                .get(NotificationReadService.TRANSITION_COUNTER)
                .tags("operation", "unread", "result", "unchanged")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void unreadOnlyListingExcludesReadRows() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery read = store.add(delivery(tenant, "read@example.com"));
    NotificationDelivery unread = store.add(delivery(tenant, "unread@example.com"));
    service.markRead(tenant, read.getId());

    List<NotificationDelivery> all = service.list(tenant, false);
    List<NotificationDelivery> unreadOnly = service.list(tenant, true);

    assertThat(all)
        .extracting(NotificationDelivery::getId)
        .containsExactlyInAnyOrder(read.getId(), unread.getId());
    assertThat(unreadOnly).extracting(NotificationDelivery::getId).containsExactly(unread.getId());
  }

  @Test
  void unreadCountMatchesTheUnreadListing() {
    UUID tenant = UUID.randomUUID();
    for (int i = 0; i < 5; i++) {
      NotificationDelivery delivery = store.add(delivery(tenant, "user" + i + "@example.com"));
      if (i % 2 == 0) {
        service.markRead(tenant, delivery.getId());
      }
    }

    long count = service.unreadCount(tenant);
    int listed = service.list(tenant, true).size();

    assertThat(count).isEqualTo(listed).isEqualTo(2);
  }

  @Test
  void anotherTenantsNotificationCannotBeMarked() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    NotificationDelivery foreign = store.add(delivery(tenantB, "b@example.com"));

    assertThatThrownBy(() -> service.markRead(tenantA, foreign.getId()))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));
    assertThat(foreign.isRead()).isFalse();
    assertThat(service.unreadCount(tenantB)).isEqualTo(1);
  }

  @Test
  void unknownNotificationReturnsNotFound() {
    UUID tenant = UUID.randomUUID();

    assertThatThrownBy(() -> service.markUnread(tenant, UUID.randomUUID()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.status().value()).isEqualTo(404);
              assertThat(awap.getMessage()).contains("was not found");
            });
    assertThat(
            registry
                .get(NotificationReadService.READ_LATENCY_TIMER)
                .tags("operation", "unread", "result", "not_found")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void purgedNotificationCannotBeMarked() {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery delivery = store.add(delivery(tenant, "a@example.com"));
    store.remove(delivery.getId());

    assertThatThrownBy(() -> service.markRead(tenant, delivery.getId()))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void concurrentReadsAndUnreadsEndInADeterministicState() throws Exception {
    UUID tenant = UUID.randomUUID();
    NotificationDelivery delivery = store.add(delivery(tenant, "a@example.com"));

    runConcurrently(8, () -> service.markRead(tenant, delivery.getId()));

    assertThat(store.readTransitions()).isEqualTo(1);
    assertThat(delivery.isRead()).isTrue();
    assertThat(service.unreadCount(tenant)).isZero();

    runConcurrently(8, () -> service.markUnread(tenant, delivery.getId()));

    assertThat(store.unreadTransitions()).isEqualTo(1);
    assertThat(delivery.isRead()).isFalse();
    assertThat(service.unreadCount(tenant)).isEqualTo(1);
  }

  @Test
  void gaugeIsPublishedPerTenant() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    store.add(delivery(tenantA, "a@example.com"));
    store.add(delivery(tenantB, "b@example.com"));
    store.add(delivery(tenantB, "b2@example.com"));

    service.unreadCount(tenantA);
    service.unreadCount(tenantB);

    assertThat(gaugeValue(tenantA)).isEqualTo(1.0);
    assertThat(gaugeValue(tenantB)).isEqualTo(2.0);
  }

  private double gaugeValue(UUID tenant) {
    return registry
        .get(NotificationReadService.UNREAD_GAUGE)
        .tag("tenant", tenant.toString())
        .gauge()
        .value();
  }

  private static void runConcurrently(int threads, Runnable action) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(
            () -> {
              ready.countDown();
              try {
                start.await(5, TimeUnit.SECONDS);
                action.run();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  private static NotificationDelivery delivery(UUID tenant, String recipient) {
    return new NotificationDelivery(
        tenant, "dedupe-" + recipient, NotificationChannel.WEB, recipient, "welcome");
  }

  /** Emulates the atomic conditional UPDATE of the JPA adapter. */
  private static final class InMemoryReadStore implements NotificationReadStore {

    private final Map<UUID, NotificationDelivery> byId = new ConcurrentHashMap<>();
    private final AtomicInteger readTransitions = new AtomicInteger();
    private final AtomicInteger unreadTransitions = new AtomicInteger();

    NotificationDelivery add(NotificationDelivery delivery) {
      byId.put(delivery.getId(), delivery);
      return delivery;
    }

    void remove(UUID id) {
      byId.remove(id);
    }

    int readTransitions() {
      return readTransitions.get();
    }

    int unreadTransitions() {
      return unreadTransitions.get();
    }

    @Override
    public List<NotificationDelivery> findAll(UUID tenantId, boolean unreadOnly) {
      List<NotificationDelivery> result = new ArrayList<>();
      for (NotificationDelivery delivery : byId.values()) {
        if (tenantId.equals(delivery.getTenantId()) && (!unreadOnly || !delivery.isRead())) {
          result.add(delivery);
        }
      }
      result.sort(Comparator.comparing(NotificationDelivery::getCreatedAt).reversed());
      return result;
    }

    @Override
    public long countUnread(UUID tenantId) {
      return findAll(tenantId, true).size();
    }

    @Override
    public Optional<NotificationDelivery> findById(UUID tenantId, UUID notificationId) {
      NotificationDelivery delivery = byId.get(notificationId);
      return delivery != null && tenantId.equals(delivery.getTenantId())
          ? Optional.of(delivery)
          : Optional.empty();
    }

    @Override
    public boolean markRead(UUID tenantId, UUID notificationId, Instant at) {
      Optional<NotificationDelivery> found = findById(tenantId, notificationId);
      if (found.isEmpty()) {
        return false;
      }
      NotificationDelivery delivery = found.orElseThrow();
      synchronized (delivery) {
        boolean changed = delivery.markRead(at);
        if (changed) {
          readTransitions.incrementAndGet();
        }
        return changed;
      }
    }

    @Override
    public boolean markUnread(UUID tenantId, UUID notificationId, Instant at) {
      Optional<NotificationDelivery> found = findById(tenantId, notificationId);
      if (found.isEmpty()) {
        return false;
      }
      NotificationDelivery delivery = found.orElseThrow();
      synchronized (delivery) {
        boolean changed = delivery.markUnread(at);
        if (changed) {
          unreadTransitions.incrementAndGet();
        }
        return changed;
      }
    }
  }

  /** Clock that the test can advance to prove idempotent timestamps. */
  private static final class MutableClock extends Clock {

    private Instant instant = Instant.parse("2026-03-01T09:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }
  }
}
