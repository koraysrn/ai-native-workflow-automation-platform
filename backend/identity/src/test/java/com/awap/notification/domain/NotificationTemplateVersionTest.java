package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplateVersionTest {

  @Test
  void copiesEveryAuditedFieldFromTheTemplate() {
    UUID tenantId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    NotificationTemplate template =
        new NotificationTemplate(tenantId, "welcome", "en", "Welcome", "Hello {{name}}", actor);

    NotificationTemplateVersion version = new NotificationTemplateVersion(template);

    assertThat(version.getId()).isNotNull();
    assertThat(version.getTemplateId()).isEqualTo(template.getId());
    assertThat(version.getTenantId()).isEqualTo(tenantId);
    assertThat(version.getVersion()).isEqualTo(template.getVersion());
    assertThat(version.getSubject()).isEqualTo("Welcome");
    assertThat(version.getBody()).isEqualTo("Hello {{name}}");
    assertThat(version.isActive()).isTrue();
    assertThat(version.getCreatedBy()).isEqualTo(actor);
    assertThat(version.getCreatedAt()).isNotNull();
  }
}
