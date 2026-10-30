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
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.infrastructure.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
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
  void shorthandRenderDefaultsToEnglish() {
    assertThat(renderer.render("notification", "{\"message\":\"Hi\"}")).isEqualTo("Hi");
  }

  @Test
  void blankKeyUsesTheGenericNotificationTemplate() {
    assertThat(renderer.render(null, "  ", "en", "{\"message\":\"Hi\"}").body()).isEqualTo("Hi");
  }

  @Test
  void inlineTemplateKeyIsRenderedAsTheBody() {
    NotificationTemplateRenderer.RenderedNotificationTemplate rendered =
        renderer.render(null, "Hi {{name}}", "en", "{\"name\":\"Ada\"}");

    assertThat(rendered.subject()).isEqualTo("Notification");
    assertThat(rendered.body()).isEqualTo("Hi Ada");
  }

  @Test
  void emptyTextRendersEmpty() {
    assertThat(renderer.renderText(null, Map.of())).isEmpty();
    assertThat(renderer.renderText("", Map.of())).isEmpty();
  }

  @Test
  void malformedLocaleFallsBackToTheEnglishLookup() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(
            tenant, "welcome", "english"))
        .thenReturn(Optional.empty());
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "English {{name}}")));

    String body = renderer.render(tenant, "welcome", "english", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("English Ada");
    verify(repository)
        .findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "english");
  }

  @Test
  void nullLocaleFallsBackToTheEnglishLookup() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "English {{name}}")));

    String body = renderer.render(tenant, "welcome", null, "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("English Ada");
    verify(repository).findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en");
  }

  @Test
  void escapingCoversQuotesAndEventHandlers() {
    String escaped =
        renderer.renderText(
            "{{value}}", Map.of("value", "a&b \"c\" 'd' <img src=x onerror=alert(1)>"));

    assertThat(escaped)
        .contains("a&" + "amp;b")
        .contains("&" + "quot;c&" + "quot;")
        .contains("&" + "#39;d&" + "#39;")
        .contains("&" + "lt;img src=x onerror=alert(1)&" + "gt;");
  }

  @Test
  void invalidateWithoutArgumentsClearsEveryEntry() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));
    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");

    renderer.invalidate(null, null);
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Second body")));

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("Second body");
    assertThat(cacheCount("miss")).isEqualTo(2.0);
  }

  @Test
  void zeroTtlDisablesCaching() {
    UUID tenant = UUID.randomUUID();
    NotificationTemplateRenderer noCache =
        new NotificationTemplateRenderer(
            new ObjectMapper(), repository, registry, 0, Clock.systemUTC());
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));

    noCache.render(tenant, "welcome", "en", null);
    noCache.render(tenant, "welcome", "en", null);

    assertThat(cacheCount("miss")).isEqualTo(2.0);
  }

  @Test
  void blankVariablesJsonIsAnEmptyMap() {
    assertThat(renderer.parseVariables("  ")).isEmpty();
  }

  @Test
  void nullTemplateKeyUsesTheGenericTemplate() {
    assertThat(renderer.render((UUID) null, (String) null, "en", "{\"message\":\"Hi\"}").body())
        .isEqualTo("Hi");
  }

  @Test
  void storedEnglishMissFallsBackToTheBuiltInDefault() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.empty());

    String body = renderer.render(tenant, "welcome", "en", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("Hello Ada, welcome to AWAP.");
  }

  @Test
  void blankLocaleFallsBackToEnglish() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "English {{name}}")));

    String body = renderer.render(tenant, "welcome", "  ", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("English Ada");
  }

  @Test
  void invalidateWithNullTenantClearsTheCache() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));
    renderer.render(tenant, "welcome", "en", null);

    renderer.invalidate(null, "welcome");
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Second body")));

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("Second body");
    assertThat(cacheCount("miss")).isEqualTo(2.0);
  }

  @Test
  void invalidateWithNullKeyClearsTheCache() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));
    renderer.render(tenant, "welcome", "en", null);

    renderer.invalidate(tenant, null);
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Second body")));

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("Second body");
    assertThat(cacheCount("miss")).isEqualTo(2.0);
  }

  @Test
  void invalidateKeepsEntriesForOtherTenantsAndKeys() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));
    renderer.render(tenant, "welcome", "en", null);

    renderer.invalidate(UUID.randomUUID(), "welcome");
    renderer.invalidate(tenant, "other");

    assertThat(renderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");
    assertThat(cacheCount("hit")).isEqualTo(1.0);
  }

  @Test
  void meterlessRendererStillLooksUpTemplates() {
    UUID tenant = UUID.randomUUID();
    NotificationTemplateRenderer meterless =
        new NotificationTemplateRenderer(new ObjectMapper(), repository, null);
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Hello {{name}}")));

    String body = meterless.render(tenant, "welcome", "en", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("Hello Ada");
  }

  @Test
  void legacyRendererHandlesFailuresWithoutAMeterRegistry() {
    NotificationTemplateRenderer legacy = new NotificationTemplateRenderer(new ObjectMapper());

    assertThat(legacy.render("notification", "{\"message\":\"Hi\"}")).isEqualTo("Hi");
    assertThatThrownBy(() -> legacy.render(null, "Hi {{missing}}", "en", "{}"))
        .isInstanceOf(AwapException.class);
    legacy.invalidate(null, null);
  }

  @Test
  void looksUpTheCanonicalRegionalLocale() {
    UUID tenant = UUID.randomUUID();
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en-US"))
        .thenReturn(Optional.of(template(tenant, "en-US", "Hello {{name}}")));

    String body = renderer.render(tenant, "welcome", "en-us", "{\"name\":\"Ada\"}").body();

    assertThat(body).isEqualTo("Hello Ada");
  }

  @Test
  void malformedStoredTemplateIsRejectedAndCounted() {
    UUID tenant = UUID.randomUUID();
    NotificationTemplate stored = mock(NotificationTemplate.class);
    when(stored.getSubject()).thenReturn("Welcome");
    when(stored.getBody()).thenReturn("Hello {{name");
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> renderer.render(tenant, "welcome", "en", "{\"name\":\"Ada\"}"))
        .isInstanceOf(AwapException.class)
        .satisfies(
            ex -> {
              assertThat(((AwapException) ex).status().value()).isEqualTo(400);
              assertThat(((AwapException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            });
    assertThat(registry.get(NotificationTemplateRenderer.FAILURE_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void invalidVariablesJsonIsACountedValidationFailure() {
    AwapException failure =
        (AwapException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> renderer.render("notification", "en", "{oops"));

    assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(failure.status().value()).isEqualTo(400);
    assertThat(registry.get(NotificationTemplateRenderer.FAILURE_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void cachedTemplateExpiresAfterTheConfiguredTtl() {
    UUID tenant = UUID.randomUUID();
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    NotificationTemplateRenderer ttlRenderer =
        new NotificationTemplateRenderer(new ObjectMapper(), repository, registry, 60, clock);
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "First body")));

    assertThat(ttlRenderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");
    assertThat(ttlRenderer.render(tenant, "welcome", "en", null).body()).isEqualTo("First body");
    assertThat(cacheCount("hit")).isEqualTo(1.0);

    clock.advanceSeconds(61);
    when(repository.findByTenantIdAndTemplateKeyAndLocaleAndActiveTrue(tenant, "welcome", "en"))
        .thenReturn(Optional.of(template(tenant, "en", "Second body")));

    assertThat(ttlRenderer.render(tenant, "welcome", "en", null).body()).isEqualTo("Second body");
    assertThat(cacheCount("miss")).isEqualTo(2.0);
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

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
