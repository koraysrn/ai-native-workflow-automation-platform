package com.awap.usage.infrastructure;

import com.awap.usage.domain.TenantUsageLimit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read access for per-tenant daily usage limits. */
public interface TenantUsageLimitRepository extends JpaRepository<TenantUsageLimit, UUID> {

  @Query(value = "SELECT * FROM awap_usage_limits(:tenantId)", nativeQuery = true)
  List<TenantUsageLimit> findLimits(@Param("tenantId") UUID tenantId);
}
