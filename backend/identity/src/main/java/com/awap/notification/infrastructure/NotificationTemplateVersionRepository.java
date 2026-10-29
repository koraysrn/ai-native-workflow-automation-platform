package com.awap.notification.infrastructure;

import com.awap.notification.domain.NotificationTemplateVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for the notification template revision audit trail. */
public interface NotificationTemplateVersionRepository
    extends JpaRepository<NotificationTemplateVersion, UUID> {

  List<NotificationTemplateVersion> findByTenantIdAndTemplateIdOrderByVersionDesc(
      UUID tenantId, UUID templateId);
}
