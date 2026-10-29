package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import com.awap.notification.infrastructure.NotificationDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural proof for the TTL/business-key policy: window suppression, window rollover, tenant
 * and channel scoping, explicit keys, expired rows, the suppression meter and the inclusive TTL
 * boundary.
 */
class NotificationDedupeServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final List<NotificationDelivery> rows = new CopyOnWriteArrayList<>();
  private final CountingAdapter email = new CountingAdapter(NotificationChannel.EMAIL);
  private final CountingAdapter sms = new CountingAdapter(NotificationChannel.SMS);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final NotificationTemplateRenderer renderer =
      new NotificationTemplateRenderer(new ObjectMapper());
  private final MutableClock clock = new MutableClock(NOW);

  @BeforeEach
  void setUp() {
    when(repository.findByTenantIdAndDedupeKey(any(), any()))
        .thenAnswer(
            invocation -> {
              UUID tenantId = invocation.getArgument(0);
              String key = invocation.getArgument(1);
              return rows.stream()
                  .filter(row -> row.getTenantId().equals(tenantId))
                  .filter(row -> row.getDedupeKey().equals(key))
                  .reduce((first, second) -> second);
            });
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              NotificationDelivery delivery = invocation.getArgument(0);
              rows.removeIf(row -> row.getId().equals(delivery.getId()));
              rows.add(delivery);
              return delivery;
            });
  }

  @Test
  void sameBusinessKeyTwiceWithinTheWindowSendsOnceAndCountsTheSuppression() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    clock.advance(Duration.ofMinutes(59));
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(1);
    assertThat(rows).hasSize(1);
    assertThat(suppressed("existing")).isEqualTo(1.0);
  }

  @Test
  void sameBusinessKeyAfterTheWindowCreatesANewDelivery() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    clock.advance(Duration.ofHours(1).plusSeconds(1));
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(2);
    assertThat(rows).hasSize(2);
    assertThat(suppressed("existing")).isZero();
  }

  @Test
  void differentBusinessKeysProduceTwoSends() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-2", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(2);
    assertThat(rows).hasSize(2);
  }

  @Test
  void differentTenantsProduceTwoSendsEvenWithTheSameBusinessKey() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    send(OTHER_TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(2);
    assertThat(rows).hasSize(2);
  }

  @Test
  void differentChannelsProduceTwoSends() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    send(TENANT, NotificationChannel.SMS, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(1);
    assertThat(sms.invocations.get()).isEqualTo(1);
    assertThat(rows).hasSize(2);
  }

  @Test
  void explicitDedupeKeyInsideTheWindowSendsOnce() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", null, "explicit-key", "TASK_COMPLETED");
    send(TENANT, NotificationChannel.EMAIL, "welcome", null, "explicit-key", "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(1);
    assertThat(rows).hasSize(1);
  }

  @Test
  void anExpiredSentRowAllowsANewDeliveryRow() {
    String expiredKey =
        new com.awap.notification.domain.NotificationDedupeKeyFactory()
            .build(
                TENANT,
                NotificationChannel.EMAIL,
                "a@example.com",
                "welcome",
                com.awap.notification.domain.DedupePolicy.TRANSACTIONAL,
                "task-1",
                null,
                null);
    NotificationDelivery expired =
        new NotificationDelivery(
            TENANT,
            expiredKey,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            "task-1",
            NOW.minusSeconds(1),
            NOW.minus(Duration.ofHours(2)));
    expired.markSent();
    rows.add(expired);

    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(1);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(NotificationDelivery::getId).doesNotHaveDuplicates();
  }

  @Test
  void twoRequestsExactlyAtTheTtlBoundaryStillCreateOneRow() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    clock.advance(Duration.ofSeconds(3600));
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(1);
    assertThat(rows).hasSize(1);
  }

  @Test
  void clockRollbackKeepsTheWindowPositiveAndStillSuppressesInsideIt() {
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    clock.set(NOW.minus(Duration.ofHours(2)));

    Duration remaining = rows.getFirst().dedupeWindowRemaining(clock.instant());

    assertThat(remaining).isPositive();
    assertThat(remaining).isGreaterThan(Duration.ZERO);
    send(TENANT, NotificationChannel.EMAIL, "welcome", "task-1", null, "TASK_COMPLETED");
    assertThat(email.invocations.get()).isEqualTo(1);
  }

  @Test
  void perTemplateOverrideShortensTheWindow() {
    NotificationDedupeProperties properties = new NotificationDedupeProperties();
    properties.setDedupeTemplateWindows(Map.of("welcome", 60L));
    NotificationService service = service(properties);

    service.send(
        TENANT,
        "a@example.com",
        NotificationChannel.EMAIL,
        "welcome",
        "en",
        "{\"name\":\"Ada\"}",
        null,
        "task-1",
        "TASK_COMPLETED");
    clock.advance(Duration.ofSeconds(61));
    service.send(
        TENANT,
        "a@example.com",
        NotificationChannel.EMAIL,
        "welcome",
        "en",
        "{\"name\":\"Ada\"}",
        null,
        "task-1",
        "TASK_COMPLETED");

    assertThat(email.invocations.get()).isEqualTo(2);
    assertThat(rows).hasSize(2);
  }

  private void send(
      UUID tenantId,
      NotificationChannel channel,
      String templateKey,
      String businessKey,
      String explicitKey,
      String eventType) {
    service(new NotificationDedupeProperties())
        .send(
            tenantId,
            "a@example.com",
            channel,
            templateKey,
            "en",
            "{\"name\":\"Ada\"}",
            explicitKey,
            businessKey,
            eventType);
  }

  private NotificationService service(NotificationDedupeProperties properties) {
    return new NotificationService(
        List.of(email, sms),
        renderer,
        outbox,
        repository,
        new long[] {1_000L},
        millis -> {},
        new com.awap.notification.domain.NotificationDedupeKeyFactory(),
        new NotificationDedupeWindowResolver(properties, renderer),
        meterRegistry,
        clock);
  }

  private double suppressed(String reason) {
    var counter =
        meterRegistry.find(NotificationService.SUPPRESSED_METRIC).tag("reason", reason).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static final class CountingAdapter implements NotificationChannelAdapter {
    private final NotificationChannel channel;
    private final AtomicInteger invocations = new AtomicInteger();

    private CountingAdapter(NotificationChannel channel) {
      this.channel = channel;
    }

    @Override
    public NotificationChannel channel() {
      return channel;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      invocations.incrementAndGet();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    private void set(Instant newInstant) {
      instant = newInstant;
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
}
