package com.awap.analytics.application;

import com.awap.analytics.domain.AnalyticsEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Analytics module (ADR-003). Writes process metrics to the Micrometer registry, which exports to
 * Prometheus (the platform's time-series store) and is then consumable by Grafana/OpenSearch
 * dashboards.
 */
@Service
public class AnalyticsService {

  private final MeterRegistry meterRegistry;

  public AnalyticsService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void record(
      UUID tenantId, AnalyticsEventType type, UUID workflowInstanceId, long durationMs) {
    Timer timer =
        Timer.builder("awap.process.duration")
            .tag("tenant", tenantId.toString())
            .tag("type", type.name())
            .description("Completion time of a process step or workflow")
            .register(meterRegistry);
    timer.record(durationMs, TimeUnit.MILLISECONDS);

    if (type == AnalyticsEventType.SLA_BREACHED) {
      meterRegistry
          .counter(
              "awap.sla.breaches",
              "tenant",
              tenantId.toString(),
              "workflowInstanceId",
              String.valueOf(workflowInstanceId))
          .increment();
    }
  }
}
