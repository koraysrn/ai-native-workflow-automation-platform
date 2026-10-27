package com.awap.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.Tenant;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.usage.domain.UsageMetric;
import com.awap.usage.interfaces.dto.UsageMetricResponse;
import com.awap.usage.interfaces.dto.UsageResponse;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UsageQueryServiceTest {

  private final UUID tenantId = UUID.randomUUID();
  private final InMemoryUsageCounterStore store = new InMemoryUsageCounterStore();
  private final TenantRepository tenantRepository = mock(TenantRepository.class);
  private final UsageQueryService service = new UsageQueryService(store, tenantRepository);
  private final LocalDate from = LocalDate.of(2026, 1, 1);
  private final LocalDate to = LocalDate.of(2026, 1, 2);

  @BeforeEach
  void setUp() {
    Tenant tenant = mock(Tenant.class);
    when(tenant.getId()).thenReturn(tenantId);
    when(tenant.isDeleted()).thenReturn(false);
    when(tenantRepository.findByIdRls(tenantId)).thenReturn(Optional.of(tenant));
  }

  @Test
  void tenantWithoutUsageReportsZerosInsteadOfAnError() {
    UsageResponse response =
        service.report(tenantId, tenantId, "TENANT_ADMIN", from, to, Optional.empty());

    assertThat(response.metrics()).hasSize(UsageMetric.values().length);
    UsageMetricResponse workflow = metric(response, UsageMetric.WORKFLOW_EXECUTION);
    assertThat(workflow.total()).isZero();
    assertThat(workflow.remaining()).isEqualTo(UsageMetric.WORKFLOW_EXECUTION.defaultDailyLimit());
    assertThat(workflow.days()).isEmpty();
  }

  @Test
  void reportAggregatesPerDayValuesAndTotal() {
    store.putCounter(tenantId, from, UsageMetric.WORKFLOW_EXECUTION, 3L);
    store.putCounter(tenantId, to, UsageMetric.WORKFLOW_EXECUTION, 4L);
    store.putCounter(tenantId, from, UsageMetric.DOCUMENT_BYTES, 1024L);

    UsageResponse response =
        service.report(tenantId, tenantId, "TENANT_ADMIN", from, to, Optional.empty());

    UsageMetricResponse workflow = metric(response, UsageMetric.WORKFLOW_EXECUTION);
    assertThat(workflow.total()).isEqualTo(7L);
    assertThat(workflow.days()).hasSize(2);
    assertThat(workflow.days().get(0).value()).isEqualTo(3L);
    assertThat(workflow.days().get(1).value()).isEqualTo(4L);
    assertThat(metric(response, UsageMetric.DOCUMENT_BYTES).total()).isEqualTo(1024L);
  }

  @Test
  void remainingQuotaReflectsTheTenantLimitAndNeverGoesNegative() {
    store.setLimit(UsageMetric.AI_CALL, 5L);
    store.putCounter(tenantId, from, UsageMetric.AI_CALL, 7L);

    UsageResponse response =
        service.report(
            tenantId, tenantId, "TENANT_ADMIN", from, to, Optional.of(UsageMetric.AI_CALL));

    assertThat(response.metrics()).hasSize(1);
    UsageMetricResponse ai = response.metrics().get(0);
    assertThat(ai.limit()).isEqualTo(5L);
    assertThat(ai.remaining()).isZero();
  }

  @Test
  void metricFilterReturnsOnlyTheRequestedMetric() {
    UsageResponse response =
        service.report(
            tenantId, tenantId, "TENANT_ADMIN", from, to, Optional.of(UsageMetric.DOCUMENT_BYTES));

    assertThat(response.metrics())
        .extracting(UsageMetricResponse::metric)
        .containsExactly("document_bytes");
  }

  @Test
  void deletedTenantReturns404() {
    Tenant deleted = mock(Tenant.class);
    when(deleted.getId()).thenReturn(tenantId);
    when(deleted.isDeleted()).thenReturn(true);
    when(tenantRepository.findByIdRls(tenantId)).thenReturn(Optional.of(deleted));

    assertThatThrownBy(
            () -> service.report(tenantId, tenantId, "TENANT_ADMIN", from, to, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
              assertThat(awap.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
  }

  @Test
  void unknownTenantReturns404() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.report(unknown, unknown, "TENANT_ADMIN", from, to, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex ->
                assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND));
  }

  @Test
  void crossTenantReadWithoutSystemAdminReturns403() {
    assertThatThrownBy(
            () ->
                service.report(
                    tenantId, UUID.randomUUID(), "TENANT_ADMIN", from, to, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
              assertThat(awap.status()).isEqualTo(HttpStatus.FORBIDDEN);
            });
  }

  @Test
  void systemAdminMayReadAnotherTenant() {
    UsageResponse response =
        service.report(tenantId, UUID.randomUUID(), "SYSTEM_ADMIN", from, to, Optional.empty());

    assertThat(response.tenantId()).isEqualTo(tenantId);
  }

  @Test
  void invertedWindowReturns400() {
    assertThatThrownBy(
            () -> service.report(tenantId, tenantId, "TENANT_ADMIN", to, from, Optional.empty()))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              AwapException awap = (AwapException) ex;
              assertThat(awap.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(awap.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  private UsageMetricResponse metric(UsageResponse response, UsageMetric metric) {
    return response.metrics().stream()
        .filter(row -> row.metric().equals(metric.key()))
        .findFirst()
        .orElseThrow();
  }
}
