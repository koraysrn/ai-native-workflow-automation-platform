package com.awap.organization.infrastructure;

import com.awap.organization.domain.Department;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for departments. */
public interface DepartmentRepository extends JpaRepository<Department, UUID> {

  List<Department> findByTenantIdAndCompanyIdAndDeletedFalse(UUID tenantId, UUID companyId);

  List<Department> findByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<Department> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
