package com.awap.organization.infrastructure;

import com.awap.organization.domain.OrganizationUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for organization users. */
public interface OrganizationUserRepository extends JpaRepository<OrganizationUser, UUID> {

  List<OrganizationUser> findByTenantIdAndDeletedFalse(UUID tenantId);

  List<OrganizationUser> findByTenantIdAndTeamIdAndDeletedFalse(UUID tenantId, UUID teamId);

  List<OrganizationUser> findByTenantIdAndPositionIdAndDeletedFalse(UUID tenantId, UUID positionId);

  Optional<OrganizationUser> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

  long countByTenantIdAndDeletedFalse(UUID tenantId);

  boolean existsByTenantIdAndEmailAndDeletedFalse(UUID tenantId, String email);
}
