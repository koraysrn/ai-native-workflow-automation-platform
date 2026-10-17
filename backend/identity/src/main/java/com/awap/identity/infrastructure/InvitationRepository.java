package com.awap.identity.infrastructure;

import com.awap.identity.domain.Invitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

  /** Looks the invitation up via the SECURITY DEFINER helper (accept flow runs pre-auth). */
  @Query(
      value = "SELECT * FROM awap_find_invitation_by_token_hash(:tokenHash)",
      nativeQuery = true)
  Optional<Invitation> findByTokenHash(String tokenHash);

  boolean existsByTenantIdAndEmailAndConsumedFalse(UUID tenantId, String email);
}
