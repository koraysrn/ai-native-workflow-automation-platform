package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.domain.NotificationRetryPolicy;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Proves the consumer boundary: event metadata reaches the service, a duplicate record produces one
 * delivery, and a database outage escapes the listener (so the container does not commit the offset
 * and re-delivers the record).
 */
class NotificationRequestedEventListenerTest {

  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConcurrentHashMap<String, NotificationDelivery> persisted =
      new ConcurrentHashMap<>();
  private final CountingAdapter adapter = new CountingAdapter();
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final NotificationService notificationService =
      new NotificationService(
          List.of(adapter),
          renderer,
          mock(OutboxEventPublisher.class),
          repository,
          NotificationRetryPolicy.defaults(),
          1_024,
          60_000L,
          Clock.systemUTC());
  private final KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
  private final NotificationRequestedEventListener listener =
      new NotificationRequestedEventListener(objectMapper, notificationService, tenantContext);

  @BeforeEach
  void setUp() {
    when(renderer.render(any(), any(), any(), any()))
        .thenReturn(
            new NotificationTemplateRenderer.RenderedNotificationTemplate("Welcome", "Hello Ada"));
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
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
  }

  @Test
  void duplicateKafkaRecordProducesOneDelivery() {
    String payload = payload("dedupe-42");

    listener.onMessage(payload);
    listener.onMessage(payload);

    org.assertj.core.api.Assertions.assertThat(adapter.invocations.get()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(persisted).hasSize(1);
  }

  @Test
  void databaseOutageEscapesTheListenerSoTheOffsetIsNotCommitted() {
    doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());

    assertThatThrownBy(() -> listener.onMessage(payload("dedupe-db-down")))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("Invalid NotificationRequestedEvent payload");
    org.assertj.core.api.Assertions.assertThat(adapter.invocations.get()).isZero();
  }

  @Test
  void rejectsMalformedPayload() {
    assertThatThrownBy(() -> listener.onMessage("{\"tenantId\":\"not-a-uuid\"}"))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("Invalid NotificationRequestedEvent payload");
    verify(tenantContext, never()).run(any(), any());
  }

  private static String payload(String dedupeKey) {
    return """
        {"tenantId":"%s","recipient":"a@example.com","channel":"EMAIL","templateKey":"welcome",
         "locale":"en","variablesJson":null,"dedupeKey":"%s","eventType":"TASK_ASSIGNED",
         "taskId":"task-1"}
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
