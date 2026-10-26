package com.awap.analytics.application;

import com.awap.analytics.domain.AnalyticsAggregate;
import com.awap.analytics.domain.AnalyticsEventType;
import com.awap.analytics.infrastructure.AnalyticsAggregateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Analytics module (ADR-003). Raw events are forwarded to the configured sink (OpenSearch) while
 * day-level aggregates are persisted in PostgreSQL for dashboard queries and Prometheus holds the
 * live time-series counters.
 */
@Service
public class AnalyticsService {

  private final MeterRegistry meterRegistry;
  private final AnalyticsAggregateRepository aggregateRepository;
  private final AnalyticsEventSink eventSink;

  /** Backward-compatible test constructor. */
  AnalyticsService(MeterRegistry meterRegistry) {
    this(meterRegistry, null, null);
  }

  @Autowired
  public AnalyticsService(
      MeterRegistry meterRegistry,
      AnalyticsAggregateRepository aggregateRepository,
      AnalyticsEventSink eventSink) {
    this.meterRegistry = meterRegistry;
    this.aggregateRepository = aggregateRepository;
    this.eventSink = eventSink;
  }

  public void record(
      UUID tenantId, AnalyticsEventType type, UUID workflowInstanceId, long durationMs) {
    record(tenantId, type, workflowInstanceId, null, durationMs);
  }

  public void record(
      UUID tenantId,
      AnalyticsEventType type,
      UUID workflowInstanceId,
      UUID taskId,
      long durationMs) {
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
    if (type == AnalyticsEventType.WORKFLOW_COMPLETED) {
      meterRegistry.counter("awap.workflow.success", "tenant", tenantId.toString()).increment();
    }
    if (type == AnalyticsEventType.WORKFLOW_FAILED) {
      meterRegistry.counter("awap.workflow.failure", "tenant", tenantId.toString()).increment();
    }
    if (type == AnalyticsEventType.TASK_COMPLETED
        || type == AnalyticsEventType.APPROVAL_COMPLETED) {
      meterRegistry.counter("awap.task.completed", "tenant", tenantId.toString()).increment();
    }

    upsertAggregate(tenantId, type, durationMs);
    writeRaw(tenantId, type, workflowInstanceId, taskId, durationMs);
  }

  public List<AnalyticsAggregate> summary(UUID tenantId, LocalDate from, LocalDate to) {
    if (aggregateRepository == null) {
      return List.of();
    }
    return aggregateRepository.findByTenantIdAndDayBetweenOrderByDayAscEventTypeAsc(
        tenantId, from, to);
  }

  private void upsertAggregate(UUID tenantId, AnalyticsEventType type, long durationMs) {
    if (aggregateRepository == null) {
      return;
    }
    LocalDate day = LocalDate.now(ZoneOffset.UTC);
    AnalyticsAggregate aggregate =
        aggregateRepository
            .findByTenantIdAndDayAndEventType(tenantId, day, type.name())
            .orElseGet(() -> new AnalyticsAggregate(tenantId, day, type.name()));
    aggregate.add(durationMs);
    aggregateRepository.save(aggregate);
  }

  private void writeRaw(
      UUID tenantId,
      AnalyticsEventType type,
      UUID workflowInstanceId,
      UUID taskId,
      long durationMs) {
    if (eventSink == null) {
      return;
    }
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("tenantId", tenantId.toString());
    document.put("eventType", type.name());
    document.put(
        "workflowInstanceId", workflowInstanceId == null ? null : workflowInstanceId.toString());
    document.put("taskId", taskId == null ? null : taskId.toString());
    document.put("durationMs", durationMs);
    document.put("timestamp", java.time.Instant.now().toString());
    eventSink.write(document);
  }
}
