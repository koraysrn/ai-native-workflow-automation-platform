package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Proves the consumer boundary: a duplicate record produces one delivery, and a database outage
 * escapes the listener (so the container does not commit the offset and re-delivers the record).
 */
class NotificationRequestedEventListenerTest {

  private static final UUID TENANT = UUID.randomUUID();

  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final ConcurrentHashMap<String, NotificationDelivery> persisted =
      new ConcurrentHashMap<>();
  private final CountingAdapter adapter = new CountingAdapter();
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final NotificationService notificationService =
      new NotificationService(
          List.of(adapter),
          renderer,
          mock(OutboxEventPublisher.class),
          repository,
          com.awap.notification.domain.NotificationRetryPolicy.defaults(),
          1_024,
          60_000L,
          java.time.Clock.systemUTC());
  private KafkaTenantContext tenantContext;
  private NotificationRequestedEventListener listener;

  @BeforeEach
  void setUp() {
    when(renderer.render(any(), any(), any())).thenReturn("Hello Ada");
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              NotificationDelivery delivery = invocation.getArgument(0);
              persisted.put(delivery.getTenantId() + ":" + delivery.getDedupeKey(), delivery);
              return delivery;
            });
    when(repository.findByTenantIdAndDedupeKey(any(), any()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(
                    persisted.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
    tenantContext = mock(KafkaTenantContext.class);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    listener =
        new NotificationRequestedEventListener(
            new ObjectMapper(), notificationService, tenantContext);
  }

  @Test
  void duplicateKafkaRecordProducesOneDelivery() {
    String payload = payload("dedupe-42");

    listener.onMessage(payload);
    listener.onMessage(payload);

    assertThat(adapter.invocations.get()).isEqualTo(1);
    assertThat(persisted).hasSize(1);
  }

  @Test
  void databaseOutageEscapesTheListenerSoTheOffsetIsNotCommitted() {
    doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());

    assertThatThrownBy(() -> listener.onMessage(payload("dedupe-db-down")))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("Invalid NotificationRequestedEvent payload");
    assertThat(adapter.invocations.get()).isZero();
  }

  @Test
  void rejectsMalformedPayload() {
    assertThatThrownBy(() -> listener.onMessage("{\"tenantId\":\"not-a-uuid\"}"))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("Invalid NotificationRequestedEvent payload");
  }

  private static String payload(String dedupeKey) {
    return """
        {"tenantId":"%s","recipient":"a@example.com","channel":"EMAIL","templateKey":"welcome",
         "locale":"en","variablesJson":"{\\"name\\":\\"Ada\\"}","dedupeKey":"%s"}
        """
        .formatted(TENANT, dedupeKey);
  }

  private static final class CountingAdapter implements NotificationChannelAdapter {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      invocations.incrementAndGet();
    }
  }
}
