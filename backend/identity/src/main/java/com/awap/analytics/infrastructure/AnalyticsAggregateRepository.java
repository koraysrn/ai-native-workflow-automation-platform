package com.awap.analytics.infrastructure;

import com.awap.analytics.domain.AnalyticsAggregate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for tenant-level analytics aggregations. */
public interface AnalyticsAggregateRepository extends JpaRepository<AnalyticsAggregate, UUID> {

  Optional<AnalyticsAggregate> findByTenantIdAndDayAndEventType(
      UUID tenantId, LocalDate day, String eventType);

  List<AnalyticsAggregate> findByTenantIdAndDayBetweenOrderByDayAscEventTypeAsc(
      UUID tenantId, LocalDate from, LocalDate to);
}
