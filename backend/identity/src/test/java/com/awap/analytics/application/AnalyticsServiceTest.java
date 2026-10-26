package com.awap.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.analytics.domain.AnalyticsAggregate;
import com.awap.analytics.domain.AnalyticsEventType;
import com.awap.analytics.infrastructure.AnalyticsAggregateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.Optional;
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
    assertThat(
            meterRegistry
                .get("awap.sla.breaches")
                .tag("tenant", tenant.toString())
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void persistsTenantDayAggregateAndWritesRawEvent() {
    AnalyticsAggregateRepository repository = mock(AnalyticsAggregateRepository.class);
    AnalyticsEventSink sink = mock(AnalyticsEventSink.class);
    AnalyticsService service = new AnalyticsService(meterRegistry, repository, sink);
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    when(repository.findByTenantIdAndDayAndEventType(
            eq(tenant), any(LocalDate.class), eq("WORKFLOW_COMPLETED")))
        .thenReturn(Optional.empty());

    service.record(tenant, AnalyticsEventType.WORKFLOW_COMPLETED, instance, 1500L);

    verify(repository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                aggregate ->
                    aggregate.getEventCount() == 1
                        && aggregate.getTotalDurationMs() == 1500L
                        && aggregate.getEventType().equals("WORKFLOW_COMPLETED")));
    verify(sink)
        .write(
            org.mockito.ArgumentMatchers.argThat(
                map -> "WORKFLOW_COMPLETED".equals(map.get("eventType"))));
  }

  @Test
  void updatesAnExistingAggregateInsteadOfCreatingAnotherRow() {
    AnalyticsAggregateRepository repository = mock(AnalyticsAggregateRepository.class);
    AnalyticsAggregate existing =
        new AnalyticsAggregate(UUID.randomUUID(), LocalDate.now(), "WORKFLOW_COMPLETED");
    existing.add(100L);
    when(repository.findByTenantIdAndDayAndEventType(
            any(), any(LocalDate.class), eq("WORKFLOW_COMPLETED")))
        .thenReturn(Optional.of(existing));
    AnalyticsService service = new AnalyticsService(meterRegistry, repository, null);

    service.record(
        UUID.randomUUID(), AnalyticsEventType.WORKFLOW_COMPLETED, UUID.randomUUID(), 400L);

    assertThat(existing.getEventCount()).isEqualTo(2);
    assertThat(existing.getTotalDurationMs()).isEqualTo(500L);
    verify(repository).save(existing);
  }

  @Test
  void incrementsWorkflowFailureCounter() {
    UUID tenant = UUID.randomUUID();
    service.record(tenant, AnalyticsEventType.WORKFLOW_FAILED, UUID.randomUUID(), 50L);

    assertThat(
            meterRegistry
                .get("awap.workflow.failure")
                .tag("tenant", tenant.toString())
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
