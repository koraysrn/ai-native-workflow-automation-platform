package com.awap.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.identity.domain.Tenant;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.TenantRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.identity.interfaces.dto.LimitsRequest;
import com.awap.identity.interfaces.dto.TenantRequest;
import com.awap.identity.interfaces.dto.TenantResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

  @Mock private TenantRepository tenantRepository;
  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private TenantService tenantService;

  @BeforeEach
  void setUp() {
    tenantService = new TenantService(tenantRepository, userRepository, passwordEncoder);
  }

  @Test
  void createTenantPersistsTenantAndAdminUser() {
    when(tenantRepository.existsBySlug("acme")).thenReturn(false);
    when(passwordEncoder.encode(anyString())).thenReturn("encoded");
    TenantRequest request =
        new TenantRequest("acme", "Acme", "admin@acme.com", "password123", "Admin User");

    TenantResponse response = tenantService.createTenant(request);

    assertThat(response.slug()).isEqualTo("acme");
    assertThat(response.plan()).isEqualTo("FREE");
    assertThat(response.active()).isTrue();
    verify(tenantRepository).save(any(Tenant.class));
    verify(userRepository).save(any(User.class));
  }

  @Test
  void createTenantRejectsDuplicateSlug() {
    when(tenantRepository.existsBySlug("acme")).thenReturn(true);
    TenantRequest request =
        new TenantRequest("acme", "Acme", "admin@acme.com", "password123", "Admin User");

    assertThatThrownBy(() -> tenantService.createTenant(request)).isInstanceOf(AwapException.class);
  }

  @Test
  void updateLimitsRejectsNonAdminCrossTenantAccess() {
    UUID targetTenantId = UUID.randomUUID();
    UUID callerTenantId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tenantService.updateLimits(
                    targetTenantId,
                    new LimitsRequest("PRO", 20, 20, 2000),
                    callerTenantId,
                    "TENANT_ADMIN"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void updateLimitsAsSystemAdminReReadsAfterRlsUpdate() {
    UUID targetTenantId = UUID.randomUUID();
    UUID callerTenantId = UUID.randomUUID();
    Tenant updated = new Tenant("acme", "Acme", "PRO", 20, 20, 2000);
    when(tenantRepository.findByIdRls(targetTenantId)).thenReturn(Optional.of(updated));

    TenantResponse response =
        tenantService.updateLimits(
            targetTenantId, new LimitsRequest("PRO", 20, 20, 2000), callerTenantId, "SYSTEM_ADMIN");

    assertThat(response.plan()).isEqualTo("PRO");
    assertThat(response.maxUsers()).isEqualTo(20);
    verify(tenantRepository).updateLimitsRls(targetTenantId, "PRO", 20, 20, 2000);
  }
}
