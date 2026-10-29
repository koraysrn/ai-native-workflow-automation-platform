package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationDedupeWindowResolverTest {

  private final NotificationTemplateRenderer renderer =
      new NotificationTemplateRenderer(new ObjectMapper());

  @Test
  void defaultsToOneHour() {
    NotificationDedupeWindowResolver resolver =
        new NotificationDedupeWindowResolver(new NotificationDedupeProperties(), renderer);

    assertThat(resolver.windowSeconds("welcome")).isEqualTo(3600);
    assertThat(resolver.windowSeconds(null)).isEqualTo(3600);
    assertThat(resolver.defaultWindowSeconds()).isEqualTo(3600);
  }

  @Test
  void honoursTheConfiguredDefault() {
    NotificationDedupeProperties properties = new NotificationDedupeProperties();
    properties.setDedupeWindowSeconds(120);

    NotificationDedupeWindowResolver resolver =
        new NotificationDedupeWindowResolver(properties, renderer);

    assertThat(resolver.windowSeconds("welcome")).isEqualTo(120);
  }

  @Test
  void perTemplateOverrideWinsOverTheDefault() {
    NotificationDedupeProperties properties = new NotificationDedupeProperties();
    properties.setDedupeWindowSeconds(120);
    properties.setDedupeTemplateWindows(Map.of("welcome", 30L));

    NotificationDedupeWindowResolver resolver =
        new NotificationDedupeWindowResolver(properties, renderer);

    assertThat(resolver.windowSeconds("welcome")).isEqualTo(30);
    assertThat(resolver.windowSeconds("task-assigned")).isEqualTo(120);
    assertThat(resolver.templateWindows()).containsEntry("welcome", 30L);
  }

  @Test
  void rejectsAnOverrideForAnUnknownTemplate() {
    NotificationDedupeProperties properties = new NotificationDedupeProperties();
    properties.setDedupeTemplateWindows(Map.of("not-a-template", 30L));

    assertThatThrownBy(() -> new NotificationDedupeWindowResolver(properties, renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unknown notification template override")
        .hasMessageContaining("not-a-template");
  }

  @Test
  void rejectsANonPositiveOverride() {
    NotificationDedupeProperties properties = new NotificationDedupeProperties();
    properties.setDedupeTemplateWindows(Map.of("welcome", 0L));

    assertThatThrownBy(() -> new NotificationDedupeWindowResolver(properties, renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a positive number of seconds");
  }

  @Test
  void rejectsNonPositiveDefaults() {
    NotificationDedupeProperties negative = new NotificationDedupeProperties();
    negative.setDedupeWindowSeconds(-5);
    NotificationDedupeProperties zero = new NotificationDedupeProperties();
    zero.setDedupeWindowSeconds(0);

    assertThatThrownBy(() -> new NotificationDedupeWindowResolver(negative, renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dedupe-window-seconds");
    assertThatThrownBy(() -> new NotificationDedupeWindowResolver(zero, renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dedupe-window-seconds");
  }
}
