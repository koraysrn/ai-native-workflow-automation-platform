package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.NotificationDelivery;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notification inbox use cases: listing, unread counting and the idempotent read/unread
 * transitions. Every operation is tenant scoped and a cross-tenant identifier is answered with 404
 * so the endpoint cannot be used to probe foreign notification ids.
 */
@Service
public class NotificationReadService {

  static final String UNREAD_GAUGE = "awap.notification.unread.count";
  static final String READ_LATENCY_TIMER = "awap.notification.read.latency";
  static final String TRANSITION_COUNTER = "awap.notification.read.transitions";

  private static final Logger log = LoggerFactory.getLogger(NotificationReadService.class);

  private final NotificationReadStore store;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final Map<UUID, AtomicLong> observedUnread = new ConcurrentHashMap<>();

  @Autowired
  public NotificationReadService(NotificationReadStore store, MeterRegistry meterRegistry) {
    this(store, meterRegistry, Clock.systemUTC());
  }

  NotificationReadService(NotificationReadStore store, MeterRegistry meterRegistry, Clock clock) {
    this.store = store;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
  }

  /** Lists the tenant inbox; {@code unreadOnly} excludes acknowledged notifications. */
  @Transactional(readOnly = true)
  public List<NotificationDelivery> list(UUID tenantId, boolean unreadOnly) {
    return store.findAll(tenantId, unreadOnly);
  }

  /** Number of unread notifications for the tenant, also published as a gauge. */
  @Transactional(readOnly = true)
  public long unreadCount(UUID tenantId) {
    long count = store.countUnread(tenantId);
    observedUnreadGauge(tenantId).set(count);
    return count;
  }

  /** Marks one notification read; repeating the call returns the same stored state. */
  @Transactional
  public NotificationDelivery markRead(UUID tenantId, UUID notificationId) {
    return transition(tenantId, notificationId, Transition.READ);
  }

  /** Restores one notification to unread; repeating the call returns the same stored state. */
  @Transactional
  public NotificationDelivery markUnread(UUID tenantId, UUID notificationId) {
    return transition(tenantId, notificationId, Transition.UNREAD);
  }

  private NotificationDelivery transition(
      UUID tenantId, UUID notificationId, Transition transition) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String result = "error";
    try {
      Instant now = clock.instant();
      boolean changed =
          transition == Transition.READ
              ? store.markRead(tenantId, notificationId, now)
              : store.markUnread(tenantId, notificationId, now);
      NotificationDelivery delivery =
          store.findById(tenantId, notificationId).orElseThrow(() -> notFound(notificationId));
      result = changed ? "changed" : "unchanged";
      observedUnreadGauge(tenantId).set(store.countUnread(tenantId));
      return delivery;
    } catch (AwapException ex) {
      result = "not_found";
      log.debug(
          "Notification {} transition {} rejected for tenant {}: {}",
          notificationId,
          transition.tag(),
          tenantId,
          ex.getMessage());
      throw ex;
    } finally {
      sample.stop(
          Timer.builder(READ_LATENCY_TIMER)
              .tag("operation", transition.tag())
              .tag("result", result)
              .register(meterRegistry));
      meterRegistry
          .counter(TRANSITION_COUNTER, "operation", transition.tag(), "result", result)
          .increment();
    }
  }

  private AwapException notFound(UUID notificationId) {
    return new AwapException(
        ErrorCode.RESOURCE_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Notification " + notificationId + " was not found.");
  }

  /**
   * Keeps the last observed unread count per tenant. A scrape-time database read is not an option:
   * scheduled metrics collection runs without a tenant context, so row-level security would report
   * zero for every tenant.
   */
  private AtomicLong observedUnreadGauge(UUID tenantId) {
    return observedUnread.computeIfAbsent(
        tenantId,
        id -> {
          AtomicLong value = new AtomicLong();
          Gauge.builder(UNREAD_GAUGE, value, AtomicLong::get)
              .description("Unread in-app notifications observed for the tenant")
              .tag("tenant", id.toString())
              .register(meterRegistry);
          return value;
        });
  }

  /** Read/unread transition kinds. */
  private enum Transition {
    READ("read"),
    UNREAD("unread");

    private final String tag;

    Transition(String tag) {
      this.tag = tag;
    }

    String tag() {
      return tag;
    }
  }
}
