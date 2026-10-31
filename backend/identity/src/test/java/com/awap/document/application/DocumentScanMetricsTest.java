package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DocumentScanMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DocumentScanMetrics metrics = new DocumentScanMetrics(registry);

  @Test
  void gaugeTracksOpenConnectionsAndClampsAtZero() {
    assertThat(metrics.activeConnections()).isZero();
    assertThat(registry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value()).isZero();

    metrics.connectionOpened();
    metrics.connectionOpened();
    assertThat(metrics.activeConnections()).isEqualTo(2);
    assertThat(registry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value()).isEqualTo(2.0);

    metrics.connectionClosed();
    metrics.connectionClosed();
    metrics.connectionClosed();
    assertThat(metrics.activeConnections()).isZero();
    assertThat(registry.get(DocumentScanMetrics.SSE_CONNECTIONS).gauge().value()).isZero();
    assertThat(registry.get(DocumentScanMetrics.SSE_CONNECTED).counter().count()).isEqualTo(2.0);
    assertThat(registry.get(DocumentScanMetrics.SSE_DISCONNECTED).counter().count()).isEqualTo(3.0);
  }

  @Test
  void eventCounterAndLatencyTimerCoverPositiveNegativeAndNullInputs() {
    metrics.eventDelivered();
    assertThat(registry.get(DocumentScanMetrics.SSE_EVENTS).counter().count()).isEqualTo(1.0);

    metrics.recordStatusLatency(null);
    assertThat(registry.find(DocumentScanMetrics.STATUS_LATENCY).timer()).isNull();

    metrics.recordStatusLatency(Instant.now().minusSeconds(2));
    metrics.recordStatusLatency(Instant.now().plusSeconds(120));
    assertThat(registry.get(DocumentScanMetrics.STATUS_LATENCY).timer().count()).isEqualTo(2L);
  }
}
