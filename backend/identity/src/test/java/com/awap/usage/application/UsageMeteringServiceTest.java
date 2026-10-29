package com.awap.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.usage.application.UsageCounterStore.DriftRow;
import com.awap.usage.domain.UsageMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UsageMeteringServiceTest {

  private final UUID tenantId = UUID.randomUUID();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final InMemoryUsageCounterStore store = new InMemoryUsageCounterStore();
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);

  private UsageMeteringService service;

  @BeforeEach
  void setUp() {
    service = new UsageMeteringService(store, registry, false, clock);
  }

  @Test
  void recordCountsOnceAndDuplicateDeliveryIsIgnored() {
    assertThat(service.record(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-1")).isTrue();
    assertThat(service.record(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-1")).isFalse();

    assertThat(store.value(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(1L);
    assertThat(
            registry
                .get("awap.usage.increments")
                .tag("metric", "workflow_executions")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void recordSkipsZeroAndNegativeDeltas() {
    assertThat(service.record(tenantId, UsageMetric.DOCUMENT_BYTES, 0L, "evt-zero")).isFalse();
    assertThat(service.record(tenantId, UsageMetric.DOCUMENT_BYTES, -5L, "evt-negative")).isFalse();
    assertThatCode(() -> service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 0L, "evt-zero"))
        .doesNotThrowAnyException();

    assertThat(store.value(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.DOCUMENT_BYTES))
        .isZero();
  }

  @Test
  void recordAssignsTheEventToTheUtcDayRegardlessOfLocalTimezone() {
    Clock istanbulLateEvening =
        Clock.fixed(Instant.parse("2026-01-01T00:30:00+03:00"), ZoneOffset.UTC);
    UsageMeteringService istanbulService =
        new UsageMeteringService(store, registry, false, istanbulLateEvening);

    assertThat(istanbulService.record(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-boundary"))
        .isTrue();

    assertThat(store.value(tenantId, LocalDate.of(2025, 12, 31), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(1L);
    assertThat(store.value(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.WORKFLOW_EXECUTION))
        .isZero();
  }

  @Test
  void reserveAllowsExactlyTheLimitAndRejectsTheNextIncrement() {
    store.setLimit(UsageMetric.WORKFLOW_EXECUTION, 2L);

    service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-1");
    service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-2");

    assertThatThrownBy(() -> service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-3"))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
              assertThat(awap.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(awap.getMessage()).contains("workflow_executions");
            });

    assertThat(store.value(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(2L);
    assertThat(
            registry
                .get("awap.usage.quota.exceeded")
                .tag("metric", "workflow_executions")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void reserveTreatsARepeatedEventKeyAsAlreadyCounted() {
    store.setLimit(UsageMetric.WORKFLOW_EXECUTION, 1L);

    service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "same-event");
    assertThatCode(
            () -> service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "same-event"))
        .doesNotThrowAnyException();

    assertThat(store.value(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(1L);
  }

  @Test
  void reserveFailsClosedWhenMeteringIsUnavailable() {
    store.failWith(new IllegalStateException("database down"));

    assertThatThrownBy(() -> service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 1L, "evt-1"))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.USAGE_METERING_UNAVAILABLE);
              assertThat(awap.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    assertThat(
            registry
                .get("awap.usage.metering.failures")
                .tag("metric", "workflow_executions")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void reserveFailsOpenWhenConfigured() {
    store.failWith(new IllegalStateException("database down"));
    UsageMeteringService failOpenService = new UsageMeteringService(store, registry, true, clock);

    assertThatCode(() -> failOpenService.reserve(tenantId, UsageMetric.AI_CALL, 1L, "evt-1"))
        .doesNotThrowAnyException();
    assertThat(
            registry
                .get("awap.usage.metering.failures")
                .tag("metric", "ai_calls")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void recordFailOpenNeverThrows() {
    store.failWith(new IllegalStateException("database down"));

    assertThat(service.record(tenantId, UsageMetric.DOCUMENT_BYTES, 10L, "evt-1")).isFalse();
    assertThat(
            registry
                .get("awap.usage.metering.failures")
                .tag("metric", "document_bytes")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void nearLimitMetricFiresAtEightyPercent() {
    store.setLimit(UsageMetric.WORKFLOW_EXECUTION, 10L);

    service.reserve(tenantId, UsageMetric.WORKFLOW_EXECUTION, 8L, "evt-1");

    assertThat(
            registry
                .get("awap.usage.quota.near_limit")
                .tag("metric", "workflow_executions")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void effectiveLimitsCombineOverridesWithMetricDefaults() {
    store.setLimit(UsageMetric.AI_CALL, 7L);

    Map<UsageMetric, Long> limits = service.effectiveLimits(tenantId);

    assertThat(limits.get(UsageMetric.AI_CALL)).isEqualTo(7L);
    assertThat(limits.get(UsageMetric.DOCUMENT_BYTES))
        .isEqualTo(UsageMetric.DOCUMENT_BYTES.defaultDailyLimit());
    assertThat(service.limitFor(tenantId, UsageMetric.API_REQUEST))
        .isEqualTo(UsageMetric.API_REQUEST.defaultDailyLimit());
  }

  @Test
  void reconciliationCountsDriftPerMetric() {
    store.addDrift(
        new DriftRow(tenantId, LocalDate.of(2026, 1, 1), UsageMetric.WORKFLOW_EXECUTION, 3L, 5L));
    UsageReconciliationService reconciliation = new UsageReconciliationService(store, registry);

    int repaired = reconciliation.reconcile(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));

    assertThat(repaired).isEqualTo(1);
    assertThat(
            registry
                .get("awap.usage.reconciliation.drift")
                .tag("metric", "workflow_executions")
                .counter()
                .count())
        .isEqualTo(1.0d);
    assertThat(
            registry
                .get("awap.usage.reconciliation.runs")
                .tag("outcome", "repaired")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void retentionUsesConfiguredCutoff() {
    UsageRetentionService retention = new UsageRetentionService(store, registry, 400L);

    retention.archive(LocalDate.of(2027, 1, 1));

    assertThat(retention.retentionDays()).isEqualTo(400L);
    assertThat(store.archiveCalls()).isEqualTo(1);
    assertThat(store.lastArchiveCutoff()).contains(LocalDate.of(2025, 11, 27));
  }
}
