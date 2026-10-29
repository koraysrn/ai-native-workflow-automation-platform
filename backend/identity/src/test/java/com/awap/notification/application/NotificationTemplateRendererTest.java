package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.infrastructure.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplateRendererTest {

  private final NotificationTemplateRepository repository =
      mock(NotificationTemplateRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NotificationTemplateRenderer renderer =
      new NotificationTemplateRenderer(new ObjectMapper(), repository, registry);

  @Test
  void tenantTemplateWinsOverTheBuiltInDefault() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "tr"))
        .thenReturn(Optional.of(template(tenant, "tr", "Merhaba {{name}}")));

    NotificationTemplateRenderer.RenderedNotificationTemplate rendered =
        renderer.render(tenant, "welcome", "tr", "{\"name\":\"Ada\"}");

    assertThat(rendered.body()).isEqualTo("Merhaba Ada");
    assertThat(rendered.subject()).isEqualTo("Welcome");
  }

  @Test
  void missingLocaleFallsBackToEnglishTemplate() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "de"))
        .thenReturn(Optional.empty());
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "English {{name}}")));

    String body = renderer.render(tenant, "welcome", "de", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("English Ada");
  }

  @Test
  void missingStoredTemplateFallsBackToTheCodeDefault() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(any(), any(), any()))
        .thenReturn(Optional.empty());

    String body = renderer.render(tenant, "welcome", "tr", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("Merhaba Ada, AWAP'a hoş geldiniz.");
  }

  @Test
  void unknownKeyWithNoStoredTemplateRendersTheKeyAsBody() {
    String body = renderer.render("custom-key", "en", null);

    assertThat(body).isEqualTo("custom-key");
  }

  @Test
  void cachesPerTenantKeyAndLocaleAndInvalidatesOnEdit() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");
    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");
    verify(repository).findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en");
    assertThat(cacheCount("miss")).isEqualTo(1.0);
    assertThat(cacheCount("hit")).isEqualTo(1.0);

    renderer.invalidate(tenant, "welcome");
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Second body")));

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("Second body");
    assertThat(cacheCount("miss")).isEqualTo(2.0);
  }

  @Test
  void escapingPreventsXssFromVariables() {
    String body =
        renderer.render(
            "notification",
            "en",
            "{\"message\":\"<script>alert('x')</script><img src=x onerror=alert(1)>\"}");

    String lt = "&" + "lt;";
    String gt = "&" + "gt;";
    assertThat(body).doesNotContain("<script>").doesNotContain("<img");
    assertThat(body).contains(lt + "script" + gt).contains("&" + "#39;");
  }

  @Test
  void unknownPlaceholderIsRejectedAndCountedAsRenderFailure() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Hello {{name}} {{missing}}")));

    assertThatThrownBy(() -> renderer.render(tenant, "welcome", "en", "{\"name\":\"Ada\"}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThat(registry.get(NotificationTemplateRenderer.FAILURE_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void previewRenderingDoesNotTouchTheDatabaseForBuiltInTemplates() {
    String lt = "&" + "lt;";
    String gt = "&" + "gt;";
    assertThat(renderer.renderText("Hi {{name}}", java.util.Map.of("name", "<b>Ada</b>")))
        .isEqualTo("Hi " + lt + "b" + gt + "Ada" + lt + "/b" + gt);

    verify(repository, never())
        .findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(any(), eq("notification"), any());
  }

  private NotificationTemplate template(UUID tenant, String locale, String body) {
    return new NotificationTemplate(tenant, "welcome", locale, "Welcome", body, UUID.randomUUID());
  }

  private double cacheCount(String result) {
    return registry
        .get(NotificationTemplateRenderer.CACHE_METRIC)
        .tag("result", result)
        .counter()
        .count();
  }
}
