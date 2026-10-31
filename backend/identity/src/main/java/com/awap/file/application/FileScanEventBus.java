package com.awap.file.application;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * In-process fan-out bus for file scan status changes.
 *
 * <p>Subscriptions are always keyed by tenant and may additionally be narrowed to one file. There
 * is no cross-tenant shared state: a listener registered for tenant A can never observe an event
 * with a different {@code tenantId}. Dispatch happens on a bounded executor so the scan scheduler
 * never waits for a slow SSE client; a rejected delivery is dropped and the UI polling fallback
 * recovers the latest state.
 */
@Component
public class FileScanEventBus {

  private static final Logger log = LoggerFactory.getLogger(FileScanEventBus.class);

  private final Map<UUID, Set<Subscriber>> subscribersByTenant = new ConcurrentHashMap<>();
  private final Executor executor;

  public FileScanEventBus(@Qualifier("fileScanEventExecutor") Executor executor) {
    this.executor = executor;
  }

  /** Registers a tenant-scoped listener, optionally narrowed to a single file id. */
  public Subscription subscribe(
      UUID tenantId, UUID fileId, java.util.function.Consumer<FileScanStatusEvent> listener) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (listener == null) {
      throw new IllegalArgumentException("listener is required");
    }
    Subscriber subscriber = new Subscriber(UUID.randomUUID(), fileId, listener);
    subscribersByTenant
        .computeIfAbsent(tenantId, ignored -> new CopyOnWriteArraySet<>())
        .add(subscriber);
    return () -> remove(tenantId, subscriber);
  }

  /** Publishes an event asynchronously to every matching subscriber of its tenant. */
  public void publish(FileScanStatusEvent event) {
    if (event == null) {
      return;
    }
    Set<Subscriber> subscribers = subscribersByTenant.get(event.tenantId());
    if (subscribers == null || subscribers.isEmpty()) {
      return;
    }
    Set<Subscriber> snapshot = Set.copyOf(subscribers);
    try {
      executor.execute(() -> dispatch(snapshot, event));
    } catch (RejectedExecutionException ex) {
      // Bounded queue saturated by slow consumers: polling fallback in the client reconciles.
      log.debug(
          "Dropped scan status event for file {} because the dispatch queue is full",
          event.fileId());
    }
  }

  /** Number of listeners currently registered for a tenant; used by connection-limit metrics. */
  public int subscriberCount(UUID tenantId) {
    Set<Subscriber> subscribers = subscribersByTenant.get(tenantId);
    return subscribers == null ? 0 : subscribers.size();
  }

  private void dispatch(Set<Subscriber> subscribers, FileScanStatusEvent event) {
    for (Subscriber subscriber : subscribers) {
      if (subscriber.fileId() != null && !subscriber.fileId().equals(event.fileId())) {
        continue;
      }
      try {
        subscriber.listener().accept(event);
      } catch (RuntimeException ex) {
        // One broken client must not starve the other listeners of the same event.
        log.warn("Scan status subscriber failed for file {}", event.fileId(), ex);
      }
    }
  }

  private void remove(UUID tenantId, Subscriber subscriber) {
    subscribersByTenant.computeIfPresent(
        tenantId,
        (key, subscribers) -> {
          subscribers.remove(subscriber);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }

  @PreDestroy
  void closeAll() {
    subscribersByTenant.clear();
  }

  /** Closes one subscription; idempotent. */
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  private record Subscriber(
      UUID id, UUID fileId, java.util.function.Consumer<FileScanStatusEvent> listener) {}
}
