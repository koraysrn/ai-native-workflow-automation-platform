package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.application.FileScanEventBus;
import com.awap.file.application.FileScanStatusEvent;
import com.awap.file.domain.FileScanStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class DocumentScanSseServiceTest {

  private final FileScanEventBus bus = new FileScanEventBus(Runnable::run);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final DocumentScanMetrics metrics = new DocumentScanMetrics(meterRegistry);
  private final List<RecordingEmitter> emitters = new ArrayList<>();
  private DocumentScanSseService service;

  @BeforeEach
  void setUp() {
    DocumentSseEmitterFactory factory =
        new DocumentSseEmitterFactory() {
          @Override
          public SseEmitter create(long timeoutMillis) {
            RecordingEmitter emitter = new RecordingEmitter();
            emitters.add(emitter);
            return emitter;
          }
        };
    service = new DocumentScanSseService(bus, metrics, factory, 2, 60_000L);
  }

  @Test
  void opensATenantStreamSendsTheSnapshotAndStreamsLaterUpdates() {
    UUID tenant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter)
            service.open(
                tenant, user, null, List.of(event(tenant, file, FileScanStatus.QUARANTINED)));

    bus.publish(event(tenant, file, FileScanStatus.SCANNING));
    bus.publish(event(tenant, file, FileScanStatus.PROMOTED));

    assertThat(emitter.sent).hasSize(3);
    assertThat(service.activeConnections()).isEqualTo(1);
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_CONNECTED).counter().count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_EVENTS).counter().count()).isEqualTo(3.0);
    assertThat(meterRegistry.get(DocumentScanMetrics.STATUS_LATENCY).timer().count()).isEqualTo(3L);
  }

  @Test
  void perFileStreamIgnoresAnotherFilesEvent() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter)
            service.open(
                tenant,
                UUID.randomUUID(),
                file,
                List.of(event(tenant, file, FileScanStatus.CLEAN)));

    bus.publish(event(tenant, UUID.randomUUID(), FileScanStatus.INFECTED));
    bus.publish(event(tenant, file, FileScanStatus.PROMOTED));

    assertThat(emitter.sent).hasSize(2);
  }

  @Test
  void connectionLimitIsEnforcedPerUser() {
    UUID tenant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    service.open(tenant, user, null, List.of());
    service.open(tenant, user, null, List.of());

    assertThatThrownBy(() -> service.open(tenant, user, null, List.of()))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
              assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            });
    assertThat(service.activeConnections()).isEqualTo(2);
  }

  @Test
  void completionReleasesTheConnectionAndTheSubscriber() {
    UUID tenant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    RecordingEmitter emitter = (RecordingEmitter) service.open(tenant, user, null, List.of());

    emitter.complete();

    assertThat(service.activeConnections()).isZero();
    assertThat(bus.subscriberCount(tenant)).isZero();
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value()).isZero();
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_DISCONNECTED).counter().count())
        .isEqualTo(1.0);
    service.open(tenant, user, null, List.of());
  }

  @Test
  void aFailedSendReleasesTheConnectionInsteadOfLeakingIt() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());
    emitter.failSends = true;

    bus.publish(event(tenant, file, FileScanStatus.SCANNING));

    assertThat(service.activeConnections()).isZero();
    assertThat(bus.subscriberCount(tenant)).isZero();
  }

  @Test
  void timeoutCompletesAndReleasesTheStream() {
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(UUID.randomUUID(), UUID.randomUUID(), null, List.of());

    emitter.fireTimeout();

    assertThat(emitter.completed).isTrue();
    assertThat(service.activeConnections()).isZero();
    assertThat(meterRegistry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value()).isZero();
  }

  @Test
  void aNullSnapshotStillOpensTheStream() {
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(UUID.randomUUID(), UUID.randomUUID(), null, null);

    assertThat(emitter.sent).isEmpty();
    assertThat(service.activeConnections()).isEqualTo(1);
  }

  @Test
  void aFailedSnapshotSendLeavesTheClosedConnectionHarmlessForLaterEvents() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());
    emitter.failNextSend = true;

    // Re-open with a two-event snapshot; the first send fails and the second must observe the
    // connection as already closed instead of throwing or leaking it.
    service =
        new DocumentScanSseService(
            bus,
            metrics,
            new DocumentSseEmitterFactory() {
              @Override
              public SseEmitter create(long timeoutMillis) {
                emitters.add(emitter);
                return emitter;
              }
            },
            2,
            60_000L);
    service.open(
        tenant,
        UUID.randomUUID(),
        null,
        List.of(
            event(tenant, file, FileScanStatus.CLEAN),
            event(tenant, file, FileScanStatus.PROMOTED)));

    assertThat(service.activeConnections()).isZero();
  }

  @Test
  void deletingAnotherFileLeavesAPerFileStreamUntouched() {
    UUID tenant = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), file, List.of());

    service.documentDeleted(tenant, UUID.randomUUID());

    assertThat(emitter.sent).isEmpty();
    assertThat(emitter.completed).isFalse();
    assertThat(service.activeConnections()).isEqualTo(1);
  }

  @Test
  void aDeletionSendFailureReleasesTheTenantStream() {
    UUID tenant = UUID.randomUUID();
    RecordingEmitter emitter =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());
    emitter.failSends = true;

    service.documentDeleted(tenant, UUID.randomUUID());

    assertThat(service.activeConnections()).isZero();
    assertThat(bus.subscriberCount(tenant)).isZero();
  }

  @Test
  void deletingADocumentNotifiesTheTenantStreamAndCompletesPerFileStreams() {
    UUID tenant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    RecordingEmitter tenantStream = (RecordingEmitter) service.open(tenant, user, null, List.of());
    RecordingEmitter fileStream = (RecordingEmitter) service.open(tenant, user, file, List.of());

    service.documentDeleted(tenant, file);

    assertThat(tenantStream.sent).hasSize(1);
    assertThat(tenantStream.completed).isFalse();
    assertThat(fileStream.sent).hasSize(1);
    assertThat(fileStream.completed).isTrue();
    assertThat(service.activeConnections()).isEqualTo(1);
  }

  @Test
  void deletingAnotherTenantsDocumentLeavesStreamsUntouched() {
    UUID tenant = UUID.randomUUID();
    RecordingEmitter tenantStream =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());

    service.documentDeleted(UUID.randomUUID(), UUID.randomUUID());

    assertThat(tenantStream.sent).isEmpty();
    assertThat(service.activeConnections()).isEqualTo(1);
  }

  @Test
  void supportsOneThousandConcurrentStreams() {
    DocumentSseEmitterFactory factory =
        new DocumentSseEmitterFactory() {
          @Override
          public SseEmitter create(long timeoutMillis) {
            RecordingEmitter emitter = new RecordingEmitter();
            emitters.add(emitter);
            return emitter;
          }
        };
    DocumentScanSseService large =
        new DocumentScanSseService(bus, metrics, factory, 1_000, 60_000L);
    UUID tenant = UUID.randomUUID();

    for (int i = 0; i < 1_000; i++) {
      large.open(tenant, UUID.randomUUID(), null, List.of());
    }

    assertThat(large.activeConnections()).isEqualTo(1_000);
    assertThat(bus.subscriberCount(tenant)).isEqualTo(1_000);
    assertThat(emitters).hasSize(1_000);
  }

  @Test
  void heartbeatWritesACommentAndDropsDeadStreams() {
    UUID tenant = UUID.randomUUID();
    RecordingEmitter healthy =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());
    RecordingEmitter dead =
        (RecordingEmitter) service.open(tenant, UUID.randomUUID(), null, List.of());
    dead.failSends = true;

    service.heartbeat();

    assertThat(healthy.sent).hasSize(1);
    assertThat(service.activeConnections()).isEqualTo(1);
  }

  @Test
  void emitterFactoryCreatesARealEmitter() {
    assertThat(new DocumentSseEmitterFactory().create(1_000L)).isNotNull();
  }

  private FileScanStatusEvent event(UUID tenant, UUID file, FileScanStatus status) {
    return new FileScanStatusEvent(
        tenant, file, "report.pdf", status, "", Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** SseEmitter that records writes instead of requiring an initialized servlet response. */
  static final class RecordingEmitter extends SseEmitter {
    private final List<Object> sent = new ArrayList<>();
    private Runnable completion;
    private Runnable timeout;
    private Consumer<Throwable> error;
    private boolean failSends;
    private boolean failNextSend;
    private boolean completed;

    void fireTimeout() {
      if (timeout != null) {
        timeout.run();
      }
    }

    private boolean shouldFail() {
      if (failSends) {
        return true;
      }
      if (failNextSend) {
        failNextSend = false;
        return true;
      }
      return false;
    }

    @Override
    public void send(Object object) throws IOException {
      if (shouldFail()) {
        throw new IOException("client gone");
      }
      sent.add(object);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      if (shouldFail()) {
        throw new IOException("client gone");
      }
      sent.add(builder);
    }

    @Override
    public void onCompletion(Runnable callback) {
      this.completion = callback;
    }

    @Override
    public void onTimeout(Runnable callback) {
      this.timeout = callback;
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      this.error = callback;
    }

    @Override
    public void complete() {
      this.completed = true;
      if (completion != null) {
        completion.run();
      }
    }

    @Override
    public void completeWithError(Throwable ex) {
      if (error != null) {
        error.accept(ex);
      }
      complete();
    }
  }
}
