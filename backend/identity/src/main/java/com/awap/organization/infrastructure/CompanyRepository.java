package com.awap.organization.infrastructure;

import com.awap.organization.domain.Company;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for companies. */
public interface CompanyRepository extends JpaRepository<Company, UUID> {

  List<Company> findByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<Company> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
