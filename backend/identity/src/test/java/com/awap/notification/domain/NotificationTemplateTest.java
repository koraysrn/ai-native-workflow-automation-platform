package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplateTest {

  private final UUID tenantId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  private NotificationTemplate template() {
    return new NotificationTemplate(tenantId, "welcome", "en", "Welcome", "Hello {{name}}", actor);
  }

  @Test
  void newTemplateIsActiveAndOwnedByTheActor() {
    NotificationTemplate template = template();

    assertThat(template.isActive()).isTrue();
    assertThat(template.getTenantId()).isEqualTo(tenantId);
    assertThat(template.getCreatedBy()).isEqualTo(actor);
    assertThat(template.getVersion()).isZero();
  }

  @Test
  void reviseStoresNewContentAndAuditActor() {
    NotificationTemplate template = template();
    UUID editor = UUID.randomUUID();

    template.revise("Welcome back", "Hi {{name}}, welcome back.", editor);

    assertThat(template.getSubject()).isEqualTo("Welcome back");
    assertThat(template.getBody()).isEqualTo("Hi {{name}}, welcome back.");
    assertThat(template.getUpdatedBy()).isEqualTo(editor);
  }

  @Test
  void reviseRejectsMalformedPlaceholders() {
    NotificationTemplate template = template();

    assertThatThrownBy(() -> template.revise("Subject", "Broken {{name", actor))
        .isInstanceOf(InvalidNotificationTemplateException.class);
    assertThat(template.getBody()).isEqualTo("Hello {{name}}");
  }

  @Test
  void archiveAndActivateAreIdempotent() {
    NotificationTemplate template = template();

    assertThat(template.archive(actor)).isTrue();
    assertThat(template.isActive()).isFalse();
    assertThat(template.archive(actor)).isFalse();
    assertThat(template.activate(actor)).isTrue();
    assertThat(template.isActive()).isTrue();
    assertThat(template.activate(actor)).isFalse();
  }
}
