package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for tenant-owned notification templates. */
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

  List<NotificationTemplate> findByTenantIdOrderByTemplateKeyAscLocaleAsc(UUID tenantId);

  List<NotificationTemplate> findByTenantIdAndTemplateKeyOrderByLocaleAsc(
      UUID tenantId, String templateKey);

  Optional<NotificationTemplate> findByTenantIdAndTemplateKeyAndLocale(
      UUID tenantId, String templateKey, String locale);

  Optional<NotificationTemplate> findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(
      UUID tenantId, String templateKey, String locale);
}
