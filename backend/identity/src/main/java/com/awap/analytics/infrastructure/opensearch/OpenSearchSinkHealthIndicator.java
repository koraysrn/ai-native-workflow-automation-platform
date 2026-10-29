package com.awap.analytics.infrastructure.opensearch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes sink queue depth and circuit state. A broken OpenSearch does not make the application
 * unhealthy because PostgreSQL aggregates remain authoritative, so the status is {@code DEGRADED}
 * instead of {@code DOWN} while the circuit is open.
 */
@Component("openSearchAnalyticsSink")
@ConditionalOnProperty(name = "awap.analytics.opensearch.enabled", havingValue = "true")
public class OpenSearchSinkHealthIndicator implements HealthIndicator {

  private final OpenSearchAnalyticsEventSink sink;

  public OpenSearchSinkHealthIndicator(OpenSearchAnalyticsEventSink sink) {
    this.sink = sink;
  }

  @Override
  public Health health() {
    SinkCircuitBreaker.State state = sink.circuitState();
    Health.Builder builder =
        state == SinkCircuitBreaker.State.OPEN ? Health.status("DEGRADED") : Health.up();
    return builder
        .withDetail("circuitState", state.name())
        .withDetail("queueDepth", sink.queueDepth())
        .build();
  }
}
