package com.awap.organization.infrastructure;

import com.awap.organization.domain.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for teams. */
public interface TeamRepository extends JpaRepository<Team, UUID> {

  List<Team> findByTenantIdAndDepartmentIdAndDeletedFalse(UUID tenantId, UUID departmentId);

  Optional<Team> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
