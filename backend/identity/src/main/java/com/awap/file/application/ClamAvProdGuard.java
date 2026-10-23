package com.awap.file.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast guard: in production, antivirus scanning is mandatory. If {@code awap.clamav.enabled}
 * is missing or explicitly false while the {@code prod} profile is active, startup is aborted with
 * a clear error instead of silently marking every upload CLEAN.
 */
@Configuration
public class ClamAvProdGuard {

  private static final Logger log = LoggerFactory.getLogger(ClamAvProdGuard.class);

  @Bean
  @Profile("prod")
  @ConditionalOnProperty(name = "awap.clamav.enabled", havingValue = "false", matchIfMissing = true)
  public ApplicationRunner requireClamAvInProduction() {
    return new ApplicationRunner() {
      @Override
      public void run(ApplicationArguments args) {
        log.error("awap.clamav.enabled must be 'true' in the production profile.");
        throw new IllegalStateException("awap.clamav.enabled must be 'true' in production.");
      }
    };
  }
}
