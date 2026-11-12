package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Producer → consumer pipeline: the payload the notification producer writes results in exactly one
 * delivery row and one inbox row, and replaying the same lifecycle fact does not create a second.
 */
class NotificationProducerPipelineTest {

  private final UUID tenantId = UUID.randomUUID();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Map<String, NotificationDelivery> rows = new LinkedHashMap<>();
  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final RecordingAdapter adapter = new RecordingAdapter();

  private NotificationService service() {
    when(repository.findByTenantIdAndDedupeKey(eq(tenantId), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(1))));
    when(repository.save(any(NotificationDelivery.class)))
        .thenAnswer(
            invocation -> {
              NotificationDelivery delivery = invocation.getArgument(0);
              rows.put(delivery.getDedupeKey(), delivery);
              return delivery;
            });
    return new NotificationService(
        List.of(adapter),
        new NotificationTemplateRenderer(new ObjectMapper()),
        mock(OutboxEventPublisher.class),
        repository,
        com.awap.notification.domain.NotificationRetryPolicy.defaults(),
        262_144,
        0L,
        Clock.systemUTC(),
        registry,
        new com.awap.notification.domain.NotificationDedupeKeyFactory(),
        new NotificationDedupeWindowResolver(
            new NotificationDedupeProperties(),
            new NotificationTemplateRenderer(new ObjectMapper())));
  }

  @Test
  void oneLifecycleFactCreatesOneDeliveryAndOneInboxRow() {
    NotificationService service = service();

    // Exactly what NotificationEventProducer puts on the outbox row, consumed by
    // NotificationRequestedEventListener.
    service.send(
        tenantId,
        "assignee@example.com",
        NotificationChannel.EMAIL,
        "task-assigned",
        "en",
        "{\"title\":\"Approve invoice\",\"taskId\":\"task-1\"}",
        null,
        "TASK_ASSIGNED:task-1",
        "TASK_ASSIGNED");
    // A redelivered Kafka record / repeated lifecycle event for the same fact.
    service.send(
        tenantId,
        "assignee@example.com",
        NotificationChannel.EMAIL,
        "task-assigned",
        "en",
        "{\"title\":\"Approve invoice\",\"taskId\":\"task-1\"}",
        null,
        "TASK_ASSIGNED:task-1",
        "TASK_ASSIGNED");

    assertThat(rows).hasSize(1);
    NotificationDelivery delivery = rows.values().iterator().next();
    assertThat(delivery.getRecipient()).isEqualTo("assignee@example.com");
    assertThat(delivery.getTemplateKey()).isEqualTo("task-assigned");
    assertThat(delivery.getChannel()).isEqualTo(NotificationChannel.EMAIL);
    assertThat(adapter.messages).hasSize(1);
    assertThat(adapter.messages.get(0).body()).contains("You have a new task: Approve invoice.");
  }

  @Test
  void theInboxIsTenantScoped() {
    service()
        .send(
            tenantId,
            "assignee@example.com",
            NotificationChannel.EMAIL,
            "task-assigned",
            "en",
            "{\"title\":\"Approve invoice\"}",
            null,
            "TASK_ASSIGNED:task-1",
            "TASK_ASSIGNED");

    NotificationReadService readService =
        new NotificationReadService(new InboxStore(rows), registry, Clock.systemUTC());
    assertThat(readService.list(tenantId, false)).hasSize(1);
    assertThat(readService.list(UUID.randomUUID(), false)).isEmpty();
  }

  private static final class RecordingAdapter implements NotificationChannelAdapter {

    private final List<NotificationMessage> messages = new ArrayList<>();

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public String dispatch(NotificationMessage message) {
      messages.add(message);
      return null;
    }
  }

  private static final class InboxStore implements NotificationReadStore {

    private final Map<String, NotificationDelivery> rows;

    private InboxStore(Map<String, NotificationDelivery> rows) {
      this.rows = rows;
    }

    @Override
    public List<NotificationDelivery> findAll(UUID tenantId, boolean unreadOnly) {
      return rows.values().stream().filter(row -> row.getTenantId().equals(tenantId)).toList();
    }

    @Override
    public long countUnread(UUID tenantId) {
      return findAll(tenantId, true).size();
    }

    @Override
    public Optional<NotificationDelivery> findById(UUID tenantId, UUID notificationId) {
      return findAll(tenantId, false).stream()
          .filter(row -> row.getId().equals(notificationId))
          .findFirst();
    }

    @Override
    public boolean markRead(UUID tenantId, UUID notificationId, Instant at) {
      return false;
    }

    @Override
    public boolean markUnread(UUID tenantId, UUID notificationId, Instant at) {
      return false;
    }
  }
}
