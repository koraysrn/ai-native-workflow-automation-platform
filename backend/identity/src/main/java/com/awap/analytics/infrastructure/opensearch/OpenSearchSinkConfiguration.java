package com.awap.analytics.infrastructure.opensearch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the production HTTP transport for the hardened analytics sink. */
@Configuration
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchSinkConfiguration {

  @Bean
  @ConditionalOnProperty(name = "awap.analytics.opensearch.enabled", havingValue = "true")
  public OpenSearchTransport openSearchTransport(OpenSearchProperties properties) {
    return new JdkOpenSearchTransport(properties);
  }
}
