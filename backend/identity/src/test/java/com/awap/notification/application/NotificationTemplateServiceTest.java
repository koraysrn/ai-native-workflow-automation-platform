package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.domain.NotificationTemplateVersion;
import com.awap.notification.infrastructure.NotificationTemplateRepository;
import com.awap.notification.infrastructure.NotificationTemplateVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationTemplateServiceTest {

  private final NotificationTemplateRepository repository =
      mock(NotificationTemplateRepository.class);
  private final NotificationTemplateVersionRepository versions =
      mock(NotificationTemplateVersionRepository.class);
  private final NotificationTemplateRenderer renderer =
      new NotificationTemplateRenderer(new ObjectMapper());
  private final NotificationTemplateService service =
      new NotificationTemplateService(repository, versions, renderer);

  private final UUID tenantId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  @Test
  void createStoresTheTemplateAndItsFirstAuditRevision() {
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "tr"))
        .thenReturn(Optional.empty());

    NotificationTemplate created =
        service.create(tenantId, "welcome", "tr", "Merhaba", "Merhaba {{name}}", actor);

    assertThat(created.getLocale()).isEqualTo("tr");
    assertThat(created.isActive()).isTrue();
    verify(repository).saveAndFlush(created);
    ArgumentCaptor<NotificationTemplateVersion> audit =
        ArgumentCaptor.forClass(NotificationTemplateVersion.class);
    verify(versions).save(audit.capture());
    assertThat(audit.getValue().getBody()).isEqualTo("Merhaba {{name}}");
    assertThat(audit.getValue().getTemplateId()).isEqualTo(created.getId());
  }

  @Test
  void createRejectsDuplicateKeyAndLocaleWith409() {
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.of(template("welcome", "en")));

    assertThatThrownBy(
            () -> service.create(tenantId, "welcome", "en", "Hi", "Hello {{name}}", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(409));
  }

  @Test
  void createRejectsInvalidKeyAndLocale() {
    assertThatThrownBy(() -> service.create(tenantId, "Bad Key!", "en", "Hi", "Body", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThatThrownBy(() -> service.create(tenantId, "welcome", "english", "Hi", "Body", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void createRejectsOversizedBodyWith400() {
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.empty());
    String tooLarge = "a".repeat(1_048_577);

    assertThatThrownBy(() -> service.create(tenantId, "welcome", "en", "Hi", tooLarge, actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void updateWithStaleVersionReturns409() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.update(tenantId, existing.getId(), 5, "Hi", "Body", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(409));
    assertThat(existing.getBody()).isEqualTo("Hello {{name}}");
  }

  @Test
  void updateStoresTheRevisionAndAuditsIt() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    NotificationTemplate updated =
        service.update(tenantId, existing.getId(), 0, "New subject", "New {{name}} body", actor);

    assertThat(updated.getSubject()).isEqualTo("New subject");
    assertThat(updated.getBody()).isEqualTo("New {{name}} body");
    verify(versions).save(any(NotificationTemplateVersion.class));
  }

  @Test
  void anotherTenantsTemplateIsNotFound() {
    NotificationTemplate foreign = template("welcome", "en");
    when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.get(UUID.randomUUID(), foreign.getId()))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));
  }

  @Test
  void archiveIsIdempotentAndAuditedOnce() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    service.archive(tenantId, existing.getId(), actor);
    service.archive(tenantId, existing.getId(), actor);

    assertThat(existing.isActive()).isFalse();
    verify(versions, org.mockito.Mockito.times(1)).save(any(NotificationTemplateVersion.class));
  }

  @Test
  void previewRendersStoredTemplateAndEscapesVariables() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.of(existing));

    NotificationTemplateService.Preview preview =
        service.preview(
            tenantId, "welcome", "en", null, null, "{\"name\":\"<script>alert(1)</script>\"}");

    String lt = "&" + "lt;";
    String gt = "&" + "gt;";
    assertThat(preview.body())
        .isEqualTo("Hello " + lt + "script" + gt + "alert(1)" + lt + "/script" + gt);
    assertThat(preview.placeholders()).containsExactly("name");
  }

  @Test
  void previewRejectsUnknownPlaceholders() {
    assertThatThrownBy(
            () ->
                service.preview(
                    tenantId, null, null, "Hi", "Hello {{name}} {{title}}", "{\"name\":\"Ada\"}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void previewOfMissingStoredTemplateReturns404() {
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "missing", "en"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.preview(tenantId, "missing", "en", null, null, "{}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(404));
  }

  @Test
  void listFiltersByTemplateKeyWhenProvided() {
    when(repository.findByTenantIdAndTemplateKeyOrderByLocaleAsc(tenantId, "welcome"))
        .thenReturn(List.of(template("welcome", "en")));

    assertThat(service.list(tenantId, "welcome")).hasSize(1);
  }

  private NotificationTemplate template(String key, String locale) {
    return new NotificationTemplate(tenantId, key, locale, "Welcome", "Hello {{name}}", actor);
  }
}
