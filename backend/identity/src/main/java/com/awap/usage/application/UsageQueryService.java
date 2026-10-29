package com.awap.usage.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.domain.Tenant;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.usage.application.UsageCounterStore.CounterRow;
import com.awap.usage.domain.UsageMetric;
import com.awap.usage.interfaces.dto.UsageDayResponse;
import com.awap.usage.interfaces.dto.UsageMetricResponse;
import com.awap.usage.interfaces.dto.UsageResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for the tenant usage API. */
@Service
public class UsageQueryService {

  private final UsageCounterStore store;
  private final TenantRepository tenantRepository;

  public UsageQueryService(UsageCounterStore store, TenantRepository tenantRepository) {
    this.store = store;
    this.tenantRepository = tenantRepository;
  }

  @Transactional(readOnly = true)
  public UsageResponse report(
      UUID tenantId,
      UUID callerTenantId,
      String callerRole,
      LocalDate from,
      LocalDate to,
      Optional<UsageMetric> metricFilter) {
    requireAccess(tenantId, callerTenantId, callerRole);
    if (from.isAfter(to)) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "'from' must not be after 'to'.");
    }

    Tenant tenant =
        tenantRepository
            .findByIdRls(tenantId)
            .filter(candidate -> !candidate.isDeleted())
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.TENANT_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "The tenant was not found."));

    Map<UsageMetric, Long> totals = new EnumMap<>(UsageMetric.class);
    Map<UsageMetric, List<UsageDayResponse>> days = new EnumMap<>(UsageMetric.class);
    for (CounterRow row : store.counters(tenant.getId(), from, to)) {
      totals.merge(row.metric(), row.value(), Long::sum);
      days.computeIfAbsent(row.metric(), key -> new ArrayList<>())
          .add(new UsageDayResponse(row.day(), row.value()));
    }

    Map<UsageMetric, Long> limits =
        store.limits(tenant.getId()).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    UsageCounterStore.LimitRow::metric,
                    UsageCounterStore.LimitRow::dailyLimit,
                    (first, second) -> second));

    List<UsageMetricResponse> metrics = new ArrayList<>();
    for (UsageMetric metric : UsageMetric.values()) {
      if (metricFilter.isPresent() && metricFilter.get() != metric) {
        continue;
      }
      long limit = limits.getOrDefault(metric, metric.defaultDailyLimit());
      long total = totals.getOrDefault(metric, 0L);
      long remaining = limit < 0 ? -1L : Math.max(0L, limit - total);
      metrics.add(
          new UsageMetricResponse(
              metric.key(),
              metric.unit().name(),
              total,
              limit,
              remaining,
              List.copyOf(days.getOrDefault(metric, List.of()))));
    }

    return new UsageResponse(tenant.getId(), from, to, List.copyOf(metrics));
  }

  private void requireAccess(UUID tenantId, UUID callerTenantId, String callerRole) {
    if (!tenantId.equals(callerTenantId) && !"SYSTEM_ADMIN".equals(callerRole)) {
      throw new AwapException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Access denied.");
    }
  }
}
