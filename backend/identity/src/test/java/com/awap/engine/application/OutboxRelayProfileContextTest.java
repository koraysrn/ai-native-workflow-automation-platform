package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring context tests for the profile-dependent startup guard (issue #122, test plan items 7.1-7.3
 * and 8 "Spring context tests verify production fail-fast and dev success").
 *
 * <p>The guard is loaded in a real application context, so this exercises placeholder resolution
 * and the actual bean-creation failure instead of calling the constructor directly.
 */
class OutboxRelayProfileContextTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OutboxRelayConfigurationGuard.class);

  @Test
  void productionProfileFailsContextStartupWithTheJpaFallback() {
    runner
        .withPropertyValues("awap.outbox.datasource.enabled=false")
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure)
                  .isInstanceOf(BeanCreationException.class)
                  .hasStackTraceContaining("JPA outbox fallback")
                  .hasStackTraceContaining("prod");
            });
  }

  @Test
  void stagingProfileFailsContextStartupWithTheJpaFallback() {
    runner
        .withPropertyValues("awap.outbox.datasource.enabled=false")
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("JPA outbox fallback")
                  .hasStackTraceContaining("staging");
            });
  }

  @Test
  void devProfileStartsWithTheJpaFallback() {
    runner
        .withPropertyValues("awap.outbox.datasource.enabled=false")
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OutboxRelayConfigurationGuard.class).mode())
                  .isEqualTo(OutboxRelayMode.JPA);
            });
  }

  @Test
  void productionProfileStartsWithTheDedicatedPool() {
    runner
        .withPropertyValues("awap.outbox.datasource.enabled=true")
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OutboxRelayConfigurationGuard.class).mode())
                  .isEqualTo(OutboxRelayMode.JDBC);
            });
  }
}
