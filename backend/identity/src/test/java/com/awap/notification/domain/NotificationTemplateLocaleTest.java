package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationTemplateLocaleTest {

  @Test
  void keepsLanguageOnlyLocalesLowercase() {
    assertThat(NotificationTemplateLocale.canonicalize("EN")).isEqualTo("en");
    assertThat(NotificationTemplateLocale.canonicalize(" tr ")).isEqualTo("tr");
  }

  @Test
  void canonicalizesRegionToUppercase() {
    assertThat(NotificationTemplateLocale.canonicalize("en-us")).isEqualTo("en-US");
    assertThat(NotificationTemplateLocale.canonicalize("EN-GB")).isEqualTo("en-GB");
    assertThat(NotificationTemplateLocale.canonicalize("pt_br")).isEqualTo("pt-BR");
  }

  @Test
  void rejectsUnsupportedLocales() {
    assertThatThrownBy(() -> NotificationTemplateLocale.canonicalize(null))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> NotificationTemplateLocale.canonicalize(" "))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> NotificationTemplateLocale.canonicalize("english"))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("en-US");
    assertThatThrownBy(() -> NotificationTemplateLocale.canonicalize("e"))
        .isInstanceOf(InvalidNotificationTemplateException.class);
  }
}
