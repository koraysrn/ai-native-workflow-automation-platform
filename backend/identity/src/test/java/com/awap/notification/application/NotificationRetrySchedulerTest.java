package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationRetryPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationRetrySchedulerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");
  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final InMemoryNotificationDeliveryStore store = new InMemoryNotificationDeliveryStore();
  private final RecordingAdapter adapter = new RecordingAdapter();
  private final MutableClock clock = new MutableClock(NOW);
  private NotificationRetryWorker worker;
  private NotificationRetryScheduler scheduler;

  @BeforeEach
  void setUp() {
    KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
    when(tenantContext.call(any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    when(renderer.render(any(), any(), any())).thenReturn("Hello");
    worker =
        new NotificationRetryWorker(
            List.of(adapter),
            renderer,
            store,
            outbox,
            tenantContext,
            registry,
            NotificationRetryPolicy.defaults(),
            1024,
            clock);
    scheduler = scheduler(50, 5);
  }

  @Test
  void schedulerDeliversDueRowAndPublishesSentEvent() {
    UUID id =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");

    scheduler.processDueRetries();

    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(store.row(id).attempts).isEqualTo(2);
    verify(outbox)
        .publish(eq(TENANT_A), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_SENT"), any());
    assertThat(registry.get(NotificationRetryScheduler.CLAIMS_METRIC).counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get(NotificationRetryScheduler.LAG_METRIC).timer().count()).isEqualTo(1L);
  }

  @Test
  void successOnThirdAttemptClearsNextAttemptAt() {
    UUID id =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 2, "RETRYING");

    scheduler.processDueRetries();

    assertThat(store.row(id).attempts).isEqualTo(3);
    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(store.row(id).nextAttemptAt).isNull();
  }

  @Test
  void exhaustedDeliveryIsDeadLetteredAndNeverRetriedAgain() {
    UUID id =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 4, "RETRYING");
    adapter.failure = new IllegalStateException("provider unavailable");

    scheduler.processDueRetries();
    scheduler.processDueRetries();

    assertThat(store.row(id).status).isEqualTo("FAILED");
    assertThat(store.row(id).attempts).isEqualTo(5);
    assertThat(adapter.invocations.get()).isEqualTo(1);
    assertThat(registry.get(NotificationRetryWorker.DLQ_METRIC).counter().count()).isEqualTo(1.0);
  }

  @Test
  void sentRowsAreSkipped() {
    store.addSentRow(TENANT_A, "already@example.com", NOW.minusSeconds(10));

    scheduler.processDueRetries();

    assertThat(adapter.invocations.get()).isZero();
    assertThat(registry.find(NotificationRetryScheduler.CLAIMS_METRIC).counter()).isNull();
  }

  @Test
  void expiredLeaseIsTakenOverWithoutDuplicateSend() {
    UUID id =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");
    UUID staleToken = UUID.randomUUID();
    NotificationRetryClaim stale =
        store.claimDue(NOW, staleToken, Duration.ofSeconds(120), 10, 10).getFirst();

    // The replica "crashed" right after claiming: no provider call, no state write.
    clock.advance(Duration.ofSeconds(121));
    scheduler.processDueRetries();

    // The crashed replica wakes up and tries to finish with its stale lease.
    worker.deliver(stale, staleToken, NOW);

    assertThat(adapter.invocations.get()).isEqualTo(1);
    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(registry.get(NotificationRetryWorker.LEASE_CONFLICT_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void schedulerCrashResumesAfterTheLeaseExpires() {
    UUID id =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");
    store.claimDue(NOW, UUID.randomUUID(), Duration.ofSeconds(120), 10, 10);

    scheduler.processDueRetries();
    assertThat(store.row(id).status).isEqualTo("RETRYING");

    clock.advance(Duration.ofSeconds(121));
    scheduler.processDueRetries();

    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(adapter.invocations.get()).isEqualTo(1);
  }

  @Test
  void tenantIsolationKeepsEachDeliveryInItsOwnTenant() {
    UUID idA =
        store.addDueRow(TENANT_A, NotificationChannel.EMAIL, "a@example.com", NOW, 0, "PENDING");
    UUID idB =
        store.addDueRow(TENANT_B, NotificationChannel.EMAIL, "b@example.com", NOW, 0, "PENDING");

    scheduler.processDueRetries();

    assertThat(adapter.tenantsByRecipient)
        .containsEntry("a@example.com", TENANT_A)
        .containsEntry("b@example.com", TENANT_B);
    assertThat(store.row(idA).status).isEqualTo("SENT");
    assertThat(store.row(idB).status).isEqualTo("SENT");
    verify(outbox)
        .publish(eq(TENANT_A), eq("NOTIFICATION"), eq(idA), eq("NOTIFICATION_SENT"), any());
    verify(outbox)
        .publish(eq(TENANT_B), eq("NOTIFICATION"), eq(idB), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void perTenantCapPreventsOneTenantFromStarvingTheQueue() {
    scheduler = scheduler(100, 2);
    for (int i = 0; i < 10; i++) {
      store.addDueRow(
          TENANT_A, NotificationChannel.EMAIL, "a" + i + "@example.com", NOW, 0, "PENDING");
    }
    for (int i = 0; i < 2; i++) {
      store.addDueRow(
          TENANT_B, NotificationChannel.EMAIL, "b" + i + "@example.com", NOW, 0, "PENDING");
    }

    scheduler.processDueRetries();

    assertThat(registry.get(NotificationRetryScheduler.CLAIMS_METRIC).counter().count())
        .isEqualTo(4.0);
    assertThat(adapter.invocations.get()).isEqualTo(4);
    assertThat(adapter.tenantCount(TENANT_A)).isEqualTo(2);
    assertThat(adapter.tenantCount(TENANT_B)).isEqualTo(2);
  }

  @Test
  void twentyReplicasProcessOneHundredRowsExactlyOnce() throws Exception {
    scheduler = scheduler(50, 200);
    for (int i = 0; i < 100; i++) {
      UUID tenant = i % 2 == 0 ? TENANT_A : TENANT_B;
      store.addDueRow(
          tenant, NotificationChannel.EMAIL, "r" + i + "@example.com", NOW, 0, "PENDING");
    }
    int replicas = 20;
    ExecutorService pool = Executors.newFixedThreadPool(replicas);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(replicas);
    try {
      for (int replica = 0; replica < replicas; replica++) {
        pool.execute(
            () -> {
              try {
                start.await();
                for (int tick = 0; tick < 10; tick++) {
                  scheduler.processDueRetries();
                }
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(adapter.countsByRecipient).hasSize(100);
    adapter
        .countsByRecipient
        .values()
        .forEach(count -> assertThat(count.get()).as("exactly one delivery per row").isEqualTo(1));
  }

  private NotificationRetryScheduler scheduler(int batchSize, int perTenantLimit) {
    return new NotificationRetryScheduler(
        store, worker, registry, clock, batchSize, perTenantLimit, Duration.ofSeconds(120));
  }

  /** Mutable clock so a test can jump past a lease without sleeping. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      this.instant = instant.plus(duration);
    }

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
  }

  /** Adapter that records tenant/recipient pairs and supports per-recipient counting. */
  private static final class RecordingAdapter implements NotificationChannelAdapter {
    private final AtomicInteger invocations = new AtomicInteger();
    private final Map<String, UUID> tenantsByRecipient = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> countsByRecipient = new ConcurrentHashMap<>();
    private RuntimeException failure;

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      invocations.incrementAndGet();
      tenantsByRecipient.put(message.recipient(), message.tenantId());
      countsByRecipient
          .computeIfAbsent(message.recipient(), key -> new AtomicInteger())
          .incrementAndGet();
      if (failure != null) {
        throw failure;
      }
    }

    private long tenantCount(UUID tenantId) {
      return tenantsByRecipient.values().stream().filter(tenantId::equals).count();
    }
  }
}
