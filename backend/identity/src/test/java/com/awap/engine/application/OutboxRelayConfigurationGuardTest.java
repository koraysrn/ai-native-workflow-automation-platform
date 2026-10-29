package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit coverage for the startup policy of the JPA outbox fallback (issue #122): prod and staging
 * must refuse to start, dev/test must keep working, and a malformed datasource configuration must
 * never pass silently.
 */
class OutboxRelayConfigurationGuardTest {

  private static OutboxRelayConfigurationGuard guard(
      String enabled, String datasourceName, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return new OutboxRelayConfigurationGuard(environment, enabled, datasourceName);
  }

  @Test
  void productionProfileRejectsTheJpaFallbackWithAnActionableMessage() {
    assertThatThrownBy(() -> guard("false", "awap-outbox-relay", "prod"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.outbox.datasource.enabled")
        .hasMessageContaining("prod")
        .hasMessageContaining("row-level security")
        .hasMessageContaining("OUTBOX_SEPARATE_POOL");
  }

  @Test
  void stagingProfileRejectsTheJpaFallback() {
    assertThatThrownBy(() -> guard("false", "awap-outbox-relay", "staging"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("staging")
        .hasMessageContaining("not allowed");
  }

  @Test
  void devProfileAllowsTheFallbackAndReportsTheJpaMode() {
    OutboxRelayConfigurationGuard guard = guard("false", "awap-outbox-relay", "dev");

    assertThat(guard.mode()).isEqualTo(OutboxRelayMode.JPA);
    assertThat(guard.jpaFallback()).isTrue();
    assertThat(guard.productionProfiles()).isEmpty();
  }

  @Test
  void testProfileAllowsTheFallback() {
    assertThatCode(() -> guard("false", "awap-outbox-relay", "test")).doesNotThrowAnyException();
  }

  @Test
  void noActiveProfileAllowsTheFallback() {
    assertThat(guard("false", "awap-outbox-relay").mode()).isEqualTo(OutboxRelayMode.JPA);
  }

  @Test
  void productionProfileAcceptsTheDedicatedPool() {
    OutboxRelayConfigurationGuard guard = guard("true", "awap-outbox-relay", "prod");

    assertThat(guard.mode()).isEqualTo(OutboxRelayMode.JDBC);
    assertThat(guard.jpaFallback()).isFalse();
    assertThat(guard.productionProfiles()).containsExactly("prod");
    assertThat(guard.datasourceName()).isEqualTo("awap-outbox-relay");
  }

  @Test
  void mixedProfilesStillRejectTheFallbackWhenOneOfThemIsProduction() {
    assertThatThrownBy(() -> guard("false", "awap-outbox-relay", "dev", "staging"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("staging");
  }

  @Test
  void aNonBooleanEnabledValueFailsStartupEvenOutsideProduction() {
    assertThatThrownBy(() -> guard("yes", "awap-outbox-relay", "dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be exactly 'true' or 'false'")
        .hasMessageContaining("yes");
  }

  @Test
  void aMisconfiguredDatasourceNameFailsStartup() {
    assertThatThrownBy(() -> guard("true", "Awap Outbox Relay!", "prod"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.outbox.datasource.name")
        .hasMessageContaining("Awap Outbox Relay!");
  }

  @Test
  void anEmptyDatasourceNameFailsStartupWhenThePoolIsEnabled() {
    assertThatThrownBy(() -> guard("true", "  ", "dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.outbox.datasource.name");
  }

  @Test
  void aBlankDatasourceNameIsIgnoredWhenTheFallbackIsActive() {
    assertThat(guard("false", " ", "dev").datasourceName())
        .isEqualTo(OutboxRelayConfigurationGuard.DEFAULT_DATASOURCE_NAME);
  }

  @Test
  void aCustomPoolNameIsKeptForTheHealthDetails() {
    assertThat(guard("true", "awap-outbox-relay-eu", "prod").datasourceName())
        .isEqualTo("awap-outbox-relay-eu");
  }

  @Test
  void aMissingEnabledValueFailsStartupInsteadOfDefaultingSilently() {
    assertThatThrownBy(() -> guard(null, "awap-outbox-relay", "dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be exactly 'true' or 'false'");
  }

  @Test
  void aMissingDatasourceNameFailsStartupWhenThePoolIsEnabled() {
    assertThatThrownBy(() -> guard("true", null, "dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awap.outbox.datasource.name");
  }
}
