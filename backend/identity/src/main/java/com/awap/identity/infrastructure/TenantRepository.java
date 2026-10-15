package com.awap.identity.infrastructure;

import com.awap.identity.domain.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

  Optional<Tenant> findBySlug(String slug);

  boolean existsBySlug(String slug);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT * FROM awap_find_tenant_by_slug(:slug)", nativeQuery = true)
  Optional<Tenant> findBySlugRls(String slug);
}
