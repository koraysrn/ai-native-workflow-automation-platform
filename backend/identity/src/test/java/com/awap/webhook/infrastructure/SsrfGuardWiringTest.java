package com.awap.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The local stack relies on {@code WEBHOOK_SSRF_TRUSTED_HOSTS=fake-webhook}. This test proves
 * Spring actually injects that property into the single guard constructor (a second, unannotated
 * constructor previously made Spring fall back to the empty default and blocked the fake webhook).
 */
class SsrfGuardWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SsrfGuard.class)
          .withPropertyValues("awap.webhook.ssrf.trusted-hosts=fake-webhook");

  @Test
  void theTrustedHostPropertyIsInjectedIntoTheGuard() {
    runner.run(
        context ->
            assertThatCode(
                    () ->
                        context
                            .getBean(SsrfGuard.class)
                            .assertSafe("http://fake-webhook:9099/webhook"))
                .doesNotThrowAnyException());
  }
}
