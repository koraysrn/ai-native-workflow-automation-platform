package com.awap.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.analytics.domain.AnalyticsEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AnalyticsService service = new AnalyticsService(meterRegistry);

  @Test
  void recordsCompletionDurationAsTimer() {
    UUID tenant = UUID.randomUUID();
    service.record(tenant, AnalyticsEventType.WORKFLOW_COMPLETED, UUID.randomUUID(), 1500L);

    double count =
        meterRegistry.get("awap.process.duration").tag("tenant", tenant.toString()).timer().count();
    assertThat(count).isEqualTo(1.0);
  }

  @Test
  void incrementsSlaBreachCounterOnlyForSlaBreach() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    service.record(tenant, AnalyticsEventType.SLA_BREACHED, instance, 9999L);

    double breaches =
        meterRegistry.get("awap.sla.breaches").tag("tenant", tenant.toString()).counter().count();
    assertThat(breaches).isEqualTo(1.0);

    service.record(tenant, AnalyticsEventType.TASK_COMPLETED, instance, 100L);
    assertThat(meterRegistry.get("awap.sla.breaches").tag("tenant", tenant.toString()).counter().count())
        .isEqualTo(1.0);
  }
}
