package com.awap.identity.infrastructure;

import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

  long countByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<User> findFirstByTenantIdAndRole(UUID tenantId, Role role);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT * FROM awap_find_user_by_email(:tenantId, :email)",
      nativeQuery = true)
  Optional<User> findByTenantIdAndEmailRls(UUID tenantId, String email);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT awap_count_tenant_users(:tenantId)",
      nativeQuery = true)
  long countByTenantIdAndDeletedFalseRls(UUID tenantId);

  /** Records a failed login via the SECURITY DEFINER helper (RLS-bypassing write). */
  @org.springframework.data.jpa.repository.Query(
      value = "SELECT awap_record_failed_login(:userId)",
      nativeQuery = true)
  void recordFailedLogin(UUID userId);

  /** Resets the failed-login counter via the SECURITY DEFINER helper. */
  @org.springframework.data.jpa.repository.Query(
      value = "SELECT awap_reset_failed_login(:userId)",
      nativeQuery = true)
  void resetFailedLogin(UUID userId);
}
