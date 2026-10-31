package com.awap.document.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.application.FileScanEventBus;
import com.awap.file.application.FileScanStatusEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Owns tenant-scoped Server-Sent Events streams for document scan status changes.
 *
 * <p>Every connection is bound to the tenant (and user) from the authenticated request. A per-file
 * stream additionally verifies ownership before it is opened. Connections are capped per user to
 * prevent connection storms, heartbeats defeat proxy buffering, and the client is expected to fall
 * back to polling when the stream fails.
 */
@Service
public class DocumentScanSseService {

  public static final String EVENT_NAME = "scan-status";

  private static final Logger log = LoggerFactory.getLogger(DocumentScanSseService.class);

  private final FileScanEventBus eventBus;
  private final DocumentScanMetrics metrics;
  private final DocumentSseEmitterFactory emitterFactory;
  private final int maxConnectionsPerUser;
  private final long timeoutMillis;

  private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> connectionsByUser = new ConcurrentHashMap<>();

  public DocumentScanSseService(
      FileScanEventBus eventBus,
      DocumentScanMetrics metrics,
      DocumentSseEmitterFactory emitterFactory,
      @Value("${awap.documents.sse.max-connections-per-user:5}") int maxConnectionsPerUser,
      @Value("${awap.documents.sse.timeout-ms:1800000}") long timeoutMillis) {
    this.eventBus = eventBus;
    this.metrics = metrics;
    this.emitterFactory = emitterFactory;
    this.maxConnectionsPerUser = Math.max(1, maxConnectionsPerUser);
    this.timeoutMillis = Math.max(1_000L, timeoutMillis);
  }

  /**
   * Opens a tenant-scoped stream. When {@code fileId} is non-null only that file's updates are
   * forwarded; otherwise every scan update of the tenant is streamed.
   *
   * @param snapshot current statuses sent immediately so a reconnect cannot miss a transition
   */
  public SseEmitter open(
      UUID tenantId, UUID userId, UUID fileId, List<FileScanStatusEvent> snapshot) {
    reserve(userId);
    SseEmitter emitter = emitterFactory.create(timeoutMillis);
    Connection connection =
        new Connection(UUID.randomUUID(), tenantId, userId, fileId, emitter, new AtomicInteger());
    AtomicReference<Connection> reference = new AtomicReference<>(connection);

    connection.subscription =
        eventBus.subscribe(tenantId, fileId, event -> send(reference.get(), event));
    connections.put(connection.id, connection);
    metrics.connectionOpened();

    emitter.onCompletion(() -> release(connection));
    emitter.onTimeout(
        () -> {
          emitter.complete();
          release(connection);
        });
    emitter.onError(error -> release(connection));

    if (snapshot != null) {
      snapshot.forEach(event -> send(connection, event));
    }
    return emitter;
  }

  /** Sends an SSE comment to every open stream so buffering proxies flush headers and data. */
  @Scheduled(fixedDelayString = "${awap.documents.sse.heartbeat-interval-ms:15000}")
  public void heartbeat() {
    connections.values().forEach(this::sendHeartbeat);
  }

  /** Number of open streams; exposed for diagnostics and tests. */
  public int activeConnections() {
    return connections.size();
  }

  /**
   * Notifies open streams that a document was deleted. Tenant-wide streams receive the event and
   * stay open; per-file streams for that document receive it and complete cleanly.
   */
  public void documentDeleted(UUID tenantId, UUID fileId) {
    connections
        .values()
        .forEach(
            connection -> {
              if (!connection.tenantId.equals(tenantId)) {
                return;
              }
              if (connection.fileId != null && !connection.fileId.equals(fileId)) {
                return;
              }
              sendDeletion(connection, fileId);
              if (connection.fileId != null) {
                connection.emitter.complete();
                release(connection);
              }
            });
  }

  void sendHeartbeat(Connection connection) {
    try {
      connection.emitter.send(SseEmitter.event().comment("heartbeat"));
    } catch (IOException | IllegalStateException ex) {
      release(connection);
    }
  }

  private void sendDeletion(Connection connection, UUID fileId) {
    if (connection.closed.get()) {
      return;
    }
    try {
      connection.emitter.send(
          SseEmitter.event()
              .name("document-deleted")
              .data(Map.of("fileId", fileId.toString()), MediaType.APPLICATION_JSON));
      metrics.eventDelivered();
    } catch (IOException | IllegalStateException ex) {
      release(connection);
    }
  }

  private void send(Connection connection, FileScanStatusEvent event) {
    if (connection == null || connection.closed.get()) {
      return;
    }
    try {
      connection.emitter.send(
          SseEmitter.event()
              .id(connection.id + ":" + connection.sequence.incrementAndGet())
              .name(EVENT_NAME)
              .data(event, MediaType.APPLICATION_JSON));
      metrics.eventDelivered();
      metrics.recordStatusLatency(event.occurredAt());
    } catch (IOException | IllegalStateException ex) {
      release(connection);
    }
  }

  private void reserve(UUID userId) {
    AtomicInteger count = connectionsByUser.computeIfAbsent(userId, ignored -> new AtomicInteger());
    if (count.incrementAndGet() > maxConnectionsPerUser) {
      count.decrementAndGet();
      throw new AwapException(
          ErrorCode.QUOTA_EXCEEDED,
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many open document scan streams for this user.");
    }
  }

  private void release(Connection connection) {
    if (!connection.closed.compareAndSet(false, true)) {
      return;
    }
    connections.remove(connection.id);
    connectionsByUser.computeIfPresent(
        connection.userId,
        (userId, count) -> {
          int remaining = count.decrementAndGet();
          return remaining <= 0 ? null : count;
        });
    connection.subscription.close();
    metrics.connectionClosed();
    log.debug("Closed document scan stream {} for user {}", connection.id, connection.userId);
  }

  /** Metadata for one open stream. */
  static final class Connection {
    private final UUID id;
    private final UUID tenantId;
    private final UUID userId;
    private final UUID fileId;
    private final SseEmitter emitter;
    private final AtomicInteger sequence;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private FileScanEventBus.Subscription subscription = () -> {};

    private Connection(
        UUID id,
        UUID tenantId,
        UUID userId,
        UUID fileId,
        SseEmitter emitter,
        AtomicInteger sequence) {
      this.id = id;
      this.tenantId = tenantId;
      this.userId = userId;
      this.fileId = fileId;
      this.emitter = emitter;
      this.sequence = sequence;
    }
  }
}
