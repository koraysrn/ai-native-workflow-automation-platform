package com.awap.identity.infrastructure;

import com.awap.identity.domain.Invitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

  Optional<Invitation> findByTokenHash(String tokenHash);

  boolean existsByTenantIdAndEmailAndConsumedFalse(UUID tenantId, String email);
}
