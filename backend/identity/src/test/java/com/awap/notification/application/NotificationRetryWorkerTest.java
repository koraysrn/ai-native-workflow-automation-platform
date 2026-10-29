package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationRetryPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NotificationRetryWorkerTest {

  private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");
  private static final UUID TENANT = UUID.randomUUID();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final InMemoryNotificationDeliveryStore store = new InMemoryNotificationDeliveryStore();
  private final NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();
  private final CountingAdapter adapter = new CountingAdapter();
  private NotificationRetryWorker worker;

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
    when(renderer.render(any(), any(), any())).thenReturn("Hello Ada");
    worker =
        new NotificationRetryWorker(
            List.of(adapter),
            renderer,
            store,
            outbox,
            tenantContext,
            registry,
            policy,
            64,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void successMarksSentAndPublishesNotificationSent() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");
    Issued issued = claim(id, NOW);

    worker.deliver(issued.claim(), issued.token(), NOW);

    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(store.row(id).attempts).isEqualTo(2);
    assertThat(store.row(id).nextAttemptAt).isNull();
    assertThat(adapter.invocations.get()).isEqualTo(1);
    verify(outbox).publish(eq(TENANT), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_SENT"), any());
    assertThat(registry.get(NotificationRetryWorker.SUCCESSES_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void providerTimeoutIsRetriedWithBackoff() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");
    adapter.failure = new RuntimeException(new TimeoutException("read timed out"));
    Issued issued = claim(id, NOW);

    worker.deliver(issued.claim(), issued.token(), NOW);

    assertThat(store.row(id).status).isEqualTo("RETRYING");
    assertThat(store.row(id).attempts).isEqualTo(2);
    assertThat(store.row(id).nextAttemptAt).isEqualTo(NOW.plusSeconds(5));
    assertThat(store.row(id).lastError).contains("read timed out");
    assertThat(registry.get(NotificationRetryWorker.FAILURES_METRIC).counter().count())
        .isEqualTo(1.0);
    verify(outbox, never()).publish(any(), any(), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void exhaustionMarksFailedAndPublishesDlq() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 4, "RETRYING");
    adapter.failure = new IllegalStateException("provider 500");
    Issued issued = claim(id, NOW);

    worker.deliver(issued.claim(), issued.token(), NOW);

    assertThat(store.row(id).status).isEqualTo("FAILED");
    assertThat(store.row(id).attempts).isEqualTo(5);
    assertThat(store.row(id).nextAttemptAt).isNull();
    verify(outbox)
        .publish(eq(TENANT), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_FAILED"), any());
    verify(outbox).publish(eq(TENANT), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_DLQ"), any());
    assertThat(registry.get(NotificationRetryWorker.DLQ_METRIC).counter().count()).isEqualTo(1.0);
    assertThat(registry.get(NotificationRetryWorker.PERMANENT_FAILURES_METRIC).counter().count())
        .isEqualTo(1.0);

    assertThat(store.claimDue(NOW, UUID.randomUUID(), Duration.ofSeconds(1), 10, 10)).isEmpty();
  }

  @Test
  void templateRenderFailureFailsPermanentlyWithoutRetry() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 0, "RETRYING");
    when(renderer.render(any(), any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.GENERIC_INTERNAL_ERROR, HttpStatus.BAD_REQUEST, "broken template"));
    Issued issued = claim(id, NOW);

    worker.deliver(issued.claim(), issued.token(), NOW);

    assertThat(store.row(id).status).isEqualTo("FAILED");
    assertThat(store.row(id).nextAttemptAt).isNull();
    assertThat(adapter.invocations.get()).isZero();
    verify(outbox).publish(eq(TENANT), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_DLQ"), any());
    assertThat(registry.get(NotificationRetryWorker.DLQ_METRIC).counter().count()).isEqualTo(1.0);
  }

  @Test
  void oversizedPayloadFailsWithoutDispatchAndDoesNotBlockTheQueue() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 0, "RETRYING");
    when(renderer.render(any(), any(), any())).thenReturn("x".repeat(200));
    Issued issued = claim(id, NOW);

    worker.deliver(issued.claim(), issued.token(), NOW);

    assertThat(store.row(id).status).isEqualTo("FAILED");
    assertThat(store.row(id).lastError).contains("exceeds 64 bytes");
    assertThat(adapter.invocations.get()).isZero();
    verify(outbox).publish(eq(TENANT), eq("NOTIFICATION"), eq(id), eq("NOTIFICATION_DLQ"), any());
  }

  @Test
  void staleLeaseCannotDispatchAfterTakeover() {
    UUID id =
        store.addDueRow(TENANT, NotificationChannel.EMAIL, "a@example.com", NOW, 1, "RETRYING");
    Issued stale = claim(id, NOW);

    // Lease expired and another replica took it over.
    Instant later = NOW.plusSeconds(121);
    Issued current = claim(id, later);

    worker.deliver(current.claim(), current.token(), later);
    worker.deliver(stale.claim(), stale.token(), NOW);

    assertThat(adapter.invocations.get()).isEqualTo(1);
    assertThat(store.row(id).status).isEqualTo("SENT");
    assertThat(registry.get(NotificationRetryWorker.LEASE_CONFLICT_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  private Issued claim(UUID id, Instant now) {
    UUID token = UUID.randomUUID();
    List<NotificationRetryClaim> claims =
        store.claimDue(now, token, Duration.ofSeconds(120), 10, 10);
    NotificationRetryClaim claim =
        claims.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    return new Issued(claim, token);
  }

  private record Issued(NotificationRetryClaim claim, UUID token) {}

  /** Adapter that counts dispatches and can be told to fail. */
  static final class CountingAdapter implements NotificationChannelAdapter {
    private final AtomicInteger invocations = new AtomicInteger();
    private RuntimeException failure;

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      invocations.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
    }
  }
}
