package com.awap.usage.infrastructure;

import com.awap.usage.domain.UsageCounter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read access for tenant/day usage counters. */
public interface UsageCounterRepository extends JpaRepository<UsageCounter, UUID> {

  /**
   * Reads counters through the SECURITY DEFINER function so the object-level authorization done in
   * the service (own tenant or SYSTEM_ADMIN) is the single decision point, while RLS still protects
   * every direct table access.
   */
  @Query(value = "SELECT * FROM awap_usage_counters(:tenantId, :from, :to)", nativeQuery = true)
  List<UsageCounter> findUsage(
      @Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
