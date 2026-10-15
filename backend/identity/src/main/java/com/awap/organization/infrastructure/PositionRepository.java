package com.awap.organization.infrastructure;

import com.awap.organization.domain.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for positions. */
public interface PositionRepository extends JpaRepository<Position, UUID> {

  List<Position> findByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<Position> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
