package com.awap.document.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Micrometer instruments for the live document scan status stream (#135). */
@Component
public class DocumentScanMetrics {

  public static final String SSE_CONNECTIONS = "awap.document.sse.connections";
  public static final String SSE_CONNECTED = "awap.document.sse.connected";
  public static final String SSE_DISCONNECTED = "awap.document.sse.disconnected";
  public static final String SSE_EVENTS = "awap.document.sse.events";
  public static final String STATUS_LATENCY = "awap.document.scan.status.latency";

  private final MeterRegistry meterRegistry;
  private final AtomicInteger activeConnections = new AtomicInteger();

  public DocumentScanMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder(SSE_CONNECTIONS, activeConnections, AtomicInteger::get)
        .description("Number of currently open document scan SSE connections.")
        .register(meterRegistry);
  }

  /** Records a newly accepted SSE connection. */
  public void connectionOpened() {
    activeConnections.incrementAndGet();
    counter(SSE_CONNECTED).increment();
  }

  /** Records a closed (completed, timed out or failed) SSE connection. */
  public void connectionClosed() {
    activeConnections.updateAndGet(value -> Math.max(0, value - 1));
    counter(SSE_DISCONNECTED).increment();
  }

  /** Records one delivered status event. */
  public void eventDelivered() {
    counter(SSE_EVENTS).increment();
  }

  /** Records how long the status change waited before reaching the client. */
  public void recordStatusLatency(Instant occurredAt) {
    if (occurredAt == null) {
      return;
    }
    Duration latency = Duration.between(occurredAt, Instant.now());
    Timer.builder(STATUS_LATENCY)
        .description("Delay between a scan status transition and its delivery over SSE.")
        .register(meterRegistry)
        .record(latency.isNegative() ? Duration.ZERO : latency);
  }

  /** Current open connection count; exposed for tests and diagnostics. */
  public int activeConnections() {
    return activeConnections.get();
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }
}
