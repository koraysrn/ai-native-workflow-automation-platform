package com.awap.notification.application;

import com.awap.notification.domain.NotificationDedupeKeyFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the notification de-duplication configuration properties and domain factory bean. */
@Configuration
@EnableConfigurationProperties(NotificationDedupeProperties.class)
public class NotificationDedupeConfiguration {

  /** The key factory is framework-free (domain layer); this is the only wiring it needs. */
  @Bean
  public NotificationDedupeKeyFactory notificationDedupeKeyFactory() {
    return new NotificationDedupeKeyFactory();
  }
}
