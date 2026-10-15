package com.awap.identity.infrastructure;

import com.awap.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

  Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT * FROM awap_find_user_by_email(:tenantId, :email)", nativeQuery = true)
  Optional<User> findByTenantIdAndEmailRls(UUID tenantId, String email);
}
