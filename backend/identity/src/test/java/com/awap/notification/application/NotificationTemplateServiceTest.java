package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.dao.OptimisticLockingFailureException;

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
  void createCanonicalizesRegionalLocalesBeforePersisting() {
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en-US"))
        .thenReturn(Optional.empty());

    NotificationTemplate created =
        service.create(tenantId, "welcome", "en-us", "Hi", "Hello {{name}}", actor);

    assertThat(created.getLocale()).isEqualTo("en-US");
    verify(repository).findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en-US");
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

    NotificationTemplate first = service.archive(tenantId, existing.getId(), actor);
    NotificationTemplate second = service.archive(tenantId, existing.getId(), actor);

    assertThat(first).isNotNull();
    assertThat(second).isSameAs(first);
    assertThat(existing.isActive()).isFalse();
    verify(versions, org.mockito.Mockito.times(1)).save(any(NotificationTemplateVersion.class));
  }

  @Test
  void getReturnsTheTenantTemplate() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    assertThat(service.get(tenantId, existing.getId())).isSameAs(existing);
  }

  @Test
  void previewRejectsOversizedBodiesWith400() {
    String tooLarge = "a".repeat(1_048_577);

    assertThatThrownBy(() -> service.preview(tenantId, null, null, "Hi", tooLarge, "{}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void everyWriteInvalidatesTheRendererCache() {
    NotificationTemplateRenderer cached = mock(NotificationTemplateRenderer.class);
    NotificationTemplateService cachedService =
        new NotificationTemplateService(repository, versions, cached);
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.empty());

    NotificationTemplate created =
        cachedService.create(tenantId, "welcome", "en", "Hi", "Hello {{name}}", actor);
    when(repository.findById(created.getId())).thenReturn(Optional.of(created));
    cachedService.update(
        tenantId, created.getId(), created.getVersion(), "Hi", "Hello {{name}}", actor);
    cachedService.archive(tenantId, created.getId(), actor);
    cachedService.activate(tenantId, created.getId(), actor);

    verify(cached, times(4)).invalidate(tenantId, "welcome");
  }

  @Test
  void previewLooksUpTheCanonicalRegionalLocale() {
    NotificationTemplate existing = template("welcome", "en-US");
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en-US"))
        .thenReturn(Optional.of(existing));

    NotificationTemplateService.Preview preview =
        service.preview(tenantId, "welcome", "en-us", null, null, "{\"name\":\"Ada\"}");

    assertThat(preview.body()).isEqualTo("Hello Ada");
    verify(repository).findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en-US");
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
    assertThat(preview.subject()).isEqualTo("Welcome");
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

  @Test
  void listWithNullKeyReturnsEveryTenantTemplate() {
    when(repository.findByTenantIdOrderByTemplateKeyAscLocaleAsc(tenantId))
        .thenReturn(List.of(template("welcome", "en")));

    assertThat(service.list(tenantId, null)).hasSize(1);
  }

  @Test
  void createRejectsANullKey() {
    assertThatThrownBy(() -> service.create(tenantId, null, "en", "Hi", "Hello {{name}}", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void previewFetchesTheMissingSideWhenOnlyOneIsSupplied() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.of(existing));

    NotificationTemplateService.Preview preview =
        service.preview(tenantId, "welcome", "en", "Custom subject", null, "{\"name\":\"Ada\"}");

    assertThat(preview.subject()).isEqualTo("Custom subject");
    assertThat(preview.body()).isEqualTo("Hello Ada");
  }

  @Test
  void listWithoutKeyReturnsEveryTenantTemplate() {
    when(repository.findByTenantIdOrderByTemplateKeyAscLocaleAsc(tenantId))
        .thenReturn(List.of(template("welcome", "en"), template("welcome", "tr")));

    assertThat(service.list(tenantId, " ")).hasSize(2);
  }

  @Test
  void versionsReturnTheAuditTrailForTheTenant() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
    NotificationTemplateVersion revision = new NotificationTemplateVersion(existing);
    when(versions.findByTenantIdAndTemplateIdOrderByVersionDesc(tenantId, existing.getId()))
        .thenReturn(List.of(revision));

    assertThat(service.versions(tenantId, existing.getId())).containsExactly(revision);
  }

  @Test
  void activateIsIdempotentAndAuditedOnce() {
    NotificationTemplate existing = template("welcome", "en");
    existing.archive(actor);
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    NotificationTemplate first = service.activate(tenantId, existing.getId(), actor);
    NotificationTemplate second = service.activate(tenantId, existing.getId(), actor);

    assertThat(first).isNotNull();
    assertThat(second).isSameAs(first);
    assertThat(existing.isActive()).isTrue();
    verify(versions, times(1)).save(any(NotificationTemplateVersion.class));
  }

  @Test
  void updateRejectsMalformedContentWith400() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> service.update(tenantId, existing.getId(), 0, "Hi", "Broken {{name", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThat(existing.getBody()).isEqualTo("Hello {{name}}");
  }

  @Test
  void updateMapsOptimisticLockingFailureTo409() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(repository.saveAndFlush(any(NotificationTemplate.class)))
        .thenThrow(new OptimisticLockingFailureException("race"));

    assertThatThrownBy(
            () -> service.update(tenantId, existing.getId(), 0, "Hi", "Hello {{name}}", actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(409));
  }

  @Test
  void archiveMapsOptimisticLockingFailureTo409() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(repository.saveAndFlush(any(NotificationTemplate.class)))
        .thenThrow(new OptimisticLockingFailureException("race"));

    assertThatThrownBy(() -> service.archive(tenantId, existing.getId(), actor))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(409));
  }

  @Test
  void previewDefaultsToEnglishWhenLocaleIsMissing() {
    NotificationTemplate existing = template("welcome", "en");
    when(repository.findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en"))
        .thenReturn(Optional.of(existing));

    NotificationTemplateService.Preview preview =
        service.preview(tenantId, "welcome", null, null, null, "{\"name\":\"Ada\"}");

    assertThat(preview.body()).isEqualTo("Hello Ada");
    verify(repository).findByTenantIdAndTemplateKeyAndLocale(tenantId, "welcome", "en");
  }

  @Test
  void previewInlineTemplateSkipsTheStoredLookup() {
    NotificationTemplateService.Preview preview =
        service.preview(tenantId, null, null, "Hi", "Hello {{name}}", "{\"name\":\"Ada\"}");

    assertThat(preview.subject()).isEqualTo("Hi");
    assertThat(preview.body()).isEqualTo("Hello Ada");
    verify(repository, never()).findByTenantIdAndTemplateKeyAndLocale(any(), any(), any());
  }

  private NotificationTemplate template(String key, String locale) {
    return new NotificationTemplate(tenantId, key, locale, "Welcome", "Hello {{name}}", actor);
  }
}
