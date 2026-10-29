package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.domain.NotificationRetryPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class NotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-01T08:00:00Z");
  private static final UUID TENANT = UUID.randomUUID();

  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final Map<String, NotificationDelivery> persisted = new ConcurrentHashMap<>();
  private final CountingAdapter adapter = new CountingAdapter();
  private NotificationDeliveryRepository repository;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(NotificationDeliveryRepository.class);
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              NotificationDelivery delivery = invocation.getArgument(0);
              persisted.put(key(delivery.getTenantId(), delivery.getDedupeKey()), delivery);
              return delivery;
            });
    when(repository.findByTenantIdAndDedupeKey(any(), any()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(
                    persisted.get(key(invocation.getArgument(0), invocation.getArgument(1)))));
    when(renderer.render(any(), any(), any(), any())).thenReturn(rendered("Hello Ada"));
    service =
        new NotificationService(
            List.of(adapter),
            renderer,
            outbox,
            repository,
            NotificationRetryPolicy.defaults(),
            64,
            60_000L,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void firstAttemptFailureStoresRetryingAndReturnsImmediately() {
    adapter.failure = new IllegalStateException("provider 500");

    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    NotificationDelivery delivery = persisted.get(key(TENANT, "dedupe-1"));
    assertThat(delivery.getStatus()).isEqualTo("RETRYING");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW);
    assertThat(delivery.getLastError()).contains("provider 500");
    assertThat(adapter.invocations.get()).isEqualTo(1);
    verify(outbox, never()).publish(any(), any(), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void firstAttemptSuccessStoresSentAndPublishesNotificationSent() {
    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    NotificationDelivery delivery = persisted.get(key(TENANT, "dedupe-1"));
    assertThat(delivery.getStatus()).isEqualTo("SENT");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isNull();
    verify(outbox)
        .publish(
            eq(TENANT), eq("NOTIFICATION"), eq(delivery.getId()), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void providerTimeoutLeavesTheRowRetrying() {
    adapter.failure = new RuntimeException(new TimeoutException("read timed out"));

    service.send(TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    NotificationDelivery delivery = persisted.values().stream().findFirst().orElseThrow();
    assertThat(delivery.getStatus()).isEqualTo("RETRYING");
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void templateRenderFailureFailsPermanentlyWithoutRetry() {
    when(renderer.render(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("broken template"));

    service.send(TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    NotificationDelivery delivery = persisted.values().stream().findFirst().orElseThrow();
    assertThat(delivery.getStatus()).isEqualTo("FAILED");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isNull();
    assertThat(adapter.invocations.get()).isZero();
    verify(outbox)
        .publish(
            eq(TENANT), eq("NOTIFICATION"), eq(delivery.getId()), eq("NOTIFICATION_DLQ"), any());
  }

  @Test
  void oversizedPayloadFailsPermanentlyWithoutBlockingTheConsumer() {
    when(renderer.render(any(), any(), any(), any())).thenReturn(rendered("x".repeat(200)));

    service.send(TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    NotificationDelivery delivery = persisted.values().stream().findFirst().orElseThrow();
    assertThat(delivery.getStatus()).isEqualTo("FAILED");
    assertThat(delivery.getLastError()).contains("exceeds 64 bytes");
    assertThat(adapter.invocations.get()).isZero();
  }

  @Test
  void duplicateKafkaRecordProducesExactlyOneDelivery() {
    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");
    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    assertThat(adapter.invocations.get()).isEqualTo(1);
    assertThat(persisted).hasSize(1);
    verify(outbox).publish(eq(TENANT), eq("NOTIFICATION"), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void existingSentRowSuppressesADuplicate() {
    NotificationDelivery sent =
        new NotificationDelivery(
            TENANT, "dedupe-1", NotificationChannel.EMAIL, "a@example.com", "welcome");
    sent.recordAttempt(NOW);
    sent.markSent(NOW);
    persisted.put(key(TENANT, "dedupe-1"), sent);

    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    assertThat(adapter.invocations.get()).isZero();
  }

  @Test
  void databaseOutagePropagatesSoTheKafkaOffsetIsNotCommitted() {
    doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());

    assertThatThrownBy(
            () ->
                service.send(
                    TENANT,
                    "a@example.com",
                    NotificationChannel.EMAIL,
                    "welcome",
                    "en",
                    null,
                    "dedupe-1"))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(adapter.invocations.get()).isZero();
  }

  @Test
  void localeAndVariablesArePersistedForStableRetries() {
    service.send(
        TENANT,
        "a@example.com",
        NotificationChannel.EMAIL,
        "welcome",
        "tr",
        "{\"name\":\"Ada\"}",
        "dedupe-1");

    NotificationDelivery delivery = persisted.get(key(TENANT, "dedupe-1"));
    assertThat(delivery.getLocale()).isEqualTo("tr");
    assertThat(delivery.getVariablesJson()).isEqualTo("{\"name\":\"Ada\"}");
    verify(renderer).render(TENANT, "welcome", "tr", "{\"name\":\"Ada\"}");
  }

  @Test
  void rejectsUnknownChannel() {
    NotificationService noAdapters = new NotificationService(List.of(), renderer, outbox);

    assertThatThrownBy(
            () ->
                noAdapters.send(TENANT, "a@example.com", NotificationChannel.SMS, "welcome", null))
        .isInstanceOf(AwapException.class);
  }

  private static NotificationTemplateRenderer.RenderedNotificationTemplate rendered(String body) {
    return new NotificationTemplateRenderer.RenderedNotificationTemplate("Welcome", body);
  }

  private static String key(UUID tenantId, String dedupeKey) {
    return tenantId + ":" + dedupeKey;
  }

  /** Adapter that counts dispatches and can be told to fail once. */
  private static final class CountingAdapter implements NotificationChannelAdapter {
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
