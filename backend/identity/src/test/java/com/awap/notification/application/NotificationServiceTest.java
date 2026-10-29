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
import com.awap.notification.domain.NotificationDedupeKeyFactory;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.domain.NotificationRetryPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class NotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-01T08:00:00Z");
  private static final UUID TENANT = UUID.randomUUID();

  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final Map<String, NotificationDelivery> persisted = new ConcurrentHashMap<>();
  private final AtomicReference<NotificationDelivery> lastSaved = new AtomicReference<>();
  private final CountingAdapter adapter = new CountingAdapter();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private NotificationDeliveryRepository repository;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(NotificationDeliveryRepository.class);
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              NotificationDelivery delivery = invocation.getArgument(0);
              lastSaved.set(delivery);
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
            Clock.fixed(NOW, ZoneOffset.UTC),
            meterRegistry,
            new NotificationDedupeKeyFactory(),
            new NotificationDedupeWindowResolver(new NotificationDedupeProperties(), renderer));
  }

  @Test
  void firstAttemptSuccessStoresSentAndPublishesNotificationSent() {
    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    NotificationDelivery delivery = lastSaved.get();
    assertThat(delivery.getStatus()).isEqualTo("SENT");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isNull();
    assertThat(delivery.getDedupeKey()).hasSize(64);
    assertThat(delivery.getDedupeExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    verify(outbox)
        .publish(
            eq(TENANT), eq("NOTIFICATION"), eq(delivery.getId()), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void firstAttemptFailureStoresRetryingAndReturnsImmediately() {
    adapter.failure = new IllegalStateException("provider 500");

    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    NotificationDelivery delivery = lastSaved.get();
    assertThat(delivery.getStatus()).isEqualTo("RETRYING");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW);
    assertThat(delivery.getLastError()).contains("provider 500");
    assertThat(adapter.invocations).isEqualTo(1);
    verify(outbox, never()).publish(any(), any(), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void duplicateInsideTheActiveWindowIsSuppressedAndCounted() {
    NotificationDelivery existing =
        new NotificationDelivery(
            TENANT,
            dedupeKey("dedupe-1"),
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            null,
            NOW.plusSeconds(60),
            NOW.minusSeconds(10));
    existing.recordAttempt(NOW.minusSeconds(10));
    existing.markSent(NOW.minusSeconds(10));
    persisted.put(key(TENANT, existing.getDedupeKey()), existing);

    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    assertThat(adapter.invocations).isZero();
    assertThat(
            meterRegistry
                .get(NotificationService.SUPPRESSED_METRIC)
                .tag("reason", "existing")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void repeatAfterTheWindowCreatesANewDelivery() {
    NotificationDelivery expired =
        new NotificationDelivery(
            TENANT,
            dedupeKey("dedupe-1"),
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            null,
            NOW.minusSeconds(1),
            NOW.minusSeconds(7200));
    expired.recordAttempt(NOW.minusSeconds(3600));
    expired.markSent(NOW.minusSeconds(3600));
    persisted.put(key(TENANT, expired.getDedupeKey()), expired);

    service.send(
        TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", "en", null, "dedupe-1");

    assertThat(adapter.invocations).isEqualTo(1);
    assertThat(lastSaved.get().getId()).isNotEqualTo(expired.getId());
    assertThat(lastSaved.get().getStatus()).isEqualTo("SENT");
  }

  @Test
  void businessKeyIsStoredForATransactionalEvent() {
    service.send(
        TENANT,
        "a@example.com",
        NotificationChannel.EMAIL,
        "welcome",
        "en",
        null,
        null,
        "workflow-42",
        "WORKFLOW_COMPLETED");

    assertThat(lastSaved.get().getBusinessKey()).isEqualTo("workflow-42");
  }

  @Test
  void templateRenderFailureFailsPermanentlyWithoutRetry() {
    when(renderer.render(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("broken template"));

    service.send(TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    NotificationDelivery delivery = lastSaved.get();
    assertThat(delivery.getStatus()).isEqualTo("FAILED");
    assertThat(delivery.getAttempts()).isEqualTo(1);
    assertThat(delivery.getNextAttemptAt()).isNull();
    assertThat(adapter.invocations).isZero();
    verify(outbox)
        .publish(
            eq(TENANT), eq("NOTIFICATION"), eq(delivery.getId()), eq("NOTIFICATION_DLQ"), any());
  }

  @Test
  void oversizedPayloadFailsPermanentlyWithoutBlockingTheConsumer() {
    when(renderer.render(any(), any(), any(), any())).thenReturn(rendered("x".repeat(200)));

    service.send(TENANT, "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    NotificationDelivery delivery = lastSaved.get();
    assertThat(delivery.getStatus()).isEqualTo("FAILED");
    assertThat(delivery.getLastError()).contains("exceeds 64 bytes");
    assertThat(adapter.invocations).isZero();
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
    assertThat(adapter.invocations).isZero();
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

    NotificationDelivery delivery = lastSaved.get();
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

  private String dedupeKey(String explicitKey) {
    return new NotificationDedupeKeyFactory()
        .build(
            TENANT,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            com.awap.notification.domain.DedupePolicy.TRANSACTIONAL,
            null,
            null,
            explicitKey);
  }

  private static NotificationTemplateRenderer.RenderedNotificationTemplate rendered(String body) {
    return new NotificationTemplateRenderer.RenderedNotificationTemplate("Welcome", body);
  }

  private static String key(UUID tenantId, String dedupeKey) {
    return tenantId + ":" + dedupeKey;
  }

  /** Adapter that counts dispatches and can be told to fail. */
  private static final class CountingAdapter implements NotificationChannelAdapter {
    private int invocations;
    private RuntimeException failure;

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      invocations++;
      if (failure != null) {
        throw failure;
      }
    }
  }
}
