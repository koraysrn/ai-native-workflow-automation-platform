package com.awap.webhook.application;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds the retry policy parameters to configuration with safe documented defaults. */
@Configuration
public class WebhookRetryConfiguration {

  @Bean
  public WebhookRetryPolicy webhookRetryPolicy(
      @Value("${awap.webhook.retry.max-attempts:10}") int maxAttempts,
      @Value("${awap.webhook.retry.delays-ms:1000,5000,25000,120000,600000}") String delaysMs,
      @Value("${awap.webhook.retry.jitter-ratio:0.2}") double jitterRatio) {
    long[] delays =
        Arrays.stream(delaysMs.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .mapToLong(Long::parseLong)
            .toArray();
    return new WebhookRetryPolicy(maxAttempts, delays, jitterRatio, Math::random);
  }
}
