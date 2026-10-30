package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class NotificationTemplateValidatorTest {

  @Test
  void extractsPlaceholderNamesInOrder() {
    Set<String> placeholders =
        NotificationTemplateValidator.placeholders("Hi {{name}}, task {{title}} for {{ name }}");

    assertThat(placeholders).containsExactly("name", "title");
  }

  @Test
  void acceptsTemplatesWithoutPlaceholders() {
    assertThat(NotificationTemplateValidator.placeholders("Plain body")).isEmpty();
  }

  @Test
  void treatsNullTextAsHavingNoPlaceholders() {
    assertThat(NotificationTemplateValidator.placeholders(null)).isEmpty();
  }

  @Test
  void acceptsANullBodyInTheSizeCheck() {
    NotificationTemplateValidator.validateBodySize(null);
  }

  @Test
  void rejectsUnbalancedPlaceholders() {
    assertThatThrownBy(() -> NotificationTemplateValidator.placeholders("Hello {{name"))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("unbalanced");
  }

  @Test
  void rejectsMalformedPlaceholders() {
    assertThatThrownBy(() -> NotificationTemplateValidator.placeholders("Hello {{ }}"))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("{{name}}");
  }

  @Test
  void rejectsUnknownVariables() {
    assertThatThrownBy(
            () ->
                NotificationTemplateValidator.validateVariables(
                    "Hi {{name}} {{title}}", Set.of("name")))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("title");
  }

  @Test
  void acceptsFullySuppliedVariables() {
    NotificationTemplateValidator.validateVariables("Hi {{name}}", Set.of("name", "unused"));
  }

  @Test
  void rejectsOversizedBodies() {
    String tooLarge = "a".repeat(NotificationTemplateValidator.MAX_BODY_BYTES + 1);

    assertThatThrownBy(() -> NotificationTemplateValidator.validateBodySize(tooLarge))
        .isInstanceOf(InvalidNotificationTemplateException.class)
        .hasMessageContaining("1048576");
  }

  @Test
  void acceptsBodiesAtTheLimit() {
    String atLimit = "a".repeat(NotificationTemplateValidator.MAX_BODY_BYTES);

    NotificationTemplateValidator.validateBodySize(atLimit);
  }

  @Test
  void validateChecksSubjectAndBody() {
    assertThatThrownBy(() -> NotificationTemplateValidator.validate("Hi {{name", "body"))
        .isInstanceOf(InvalidNotificationTemplateException.class);
  }
}
