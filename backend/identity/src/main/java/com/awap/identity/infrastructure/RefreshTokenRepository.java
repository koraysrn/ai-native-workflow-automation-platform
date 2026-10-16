package com.awap.identity.infrastructure;

import com.awap.identity.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT * FROM awap_find_refresh_token(:tokenHash)",
      nativeQuery = true)
  Optional<RefreshToken> findByTokenHashRls(String tokenHash);

  /** Revokes a refresh token via the SECURITY DEFINER helper (RLS-bypassing write). */
  @org.springframework.data.jpa.repository.Query(
      value = "SELECT awap_revoke_refresh_token(:tokenHash)",
      nativeQuery = true)
  void revokeByTokenHash(String tokenHash);
}
