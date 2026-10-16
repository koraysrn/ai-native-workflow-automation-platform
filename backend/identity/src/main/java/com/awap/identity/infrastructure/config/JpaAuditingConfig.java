package com.awap.identity.infrastructure.config;

import com.awap.identity.infrastructure.security.TenantContext;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables Spring Data auditing and resolves the current user from the tenant context. */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

  @Bean
  public AuditorAware<UUID> auditorAware() {
    return () -> TenantContext.current().map(TenantContext.Context::userId);
  }
}
