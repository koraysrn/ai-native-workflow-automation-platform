package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDedupeKeyFactoryTest {

  private final NotificationDedupeKeyFactory factory = new NotificationDedupeKeyFactory();
  private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void sameInputsProduceTheSameBoundedSha256Key() {
    String first =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", "task-1", null, null);
    String second =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", "task-1", null, null);

    assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void nullBusinessKeyStillProducesADeterministicHash() {
    String first =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", null, null, null);
    String second =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", null, null, null);

    assertThat(first).isEqualTo(second).hasSize(64);
  }

  @Test
  void transactionalNullBusinessKeyFallsBackToTheVariablesPayload() {
    String first =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", null, "{\"n\":1}", null);
    String second =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", null, "{\"n\":2}", null);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void periodicEventsIgnoreTheVariablesPayload() {
    String first =
        factory.build(
            tenant,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            DedupePolicy.PERIODIC,
            null,
            "{\"n\":1}",
            null);
    String second =
        factory.build(
            tenant,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            DedupePolicy.PERIODIC,
            null,
            "{\"n\":2}",
            null);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void differentBusinessKeysTenantsChannelsAndTemplatesProduceDifferentKeys() {
    String base =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", "task-1", null, null);

    assertThat(
            key(
                tenant,
                NotificationChannel.EMAIL,
                "a@example.com",
                "welcome",
                "task-2",
                null,
                null))
        .isNotEqualTo(base);
    assertThat(
            key(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                NotificationChannel.EMAIL,
                "a@example.com",
                "welcome",
                "task-1",
                null,
                null))
        .isNotEqualTo(base);
    assertThat(
            key(tenant, NotificationChannel.SMS, "a@example.com", "welcome", "task-1", null, null))
        .isNotEqualTo(base);
    assertThat(
            key(
                tenant,
                NotificationChannel.EMAIL,
                "a@example.com",
                "task-assigned",
                "task-1",
                null,
                null))
        .isNotEqualTo(base);
  }

  @Test
  void explicitKeysAreHashedAndTenantScoped() {
    String first =
        key(
            tenant,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            null,
            null,
            "explicit-1");
    String second =
        key(
            tenant,
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            null,
            null,
            "explicit-1");
    String otherTenant =
        key(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            NotificationChannel.EMAIL,
            "a@example.com",
            "welcome",
            null,
            null,
            "explicit-1");

    assertThat(first).isEqualTo(second).hasSize(64).doesNotContain("explicit-1");
    assertThat(first).isNotEqualTo(otherTenant);
  }

  @Test
  void extremelyLongBusinessKeysAreBoundedToOneSha256Digest() {
    String longKey = "b".repeat(100_000);

    String first =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", longKey, null, null);
    String second =
        key(tenant, NotificationChannel.EMAIL, "a@example.com", "welcome", longKey, null, null);

    assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void nullChannelRecipientAndTemplateDoNotThrow() {
    String key = key(tenant, null, null, null, null, null, null);

    assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
  }

  private String key(
      UUID tenantId,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      String businessKey,
      String variablesJson,
      String explicitKey) {
    return factory.build(
        tenantId,
        channel,
        recipient,
        templateKey,
        DedupePolicy.TRANSACTIONAL,
        businessKey,
        variablesJson,
        explicitKey);
  }
}
