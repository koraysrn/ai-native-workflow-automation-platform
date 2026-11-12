package com.awap.notification.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the provider transport settings bound from {@code awap.notification.providers.*}. */
@Configuration
@EnableConfigurationProperties(NotificationProviderProperties.class)
public class NotificationProviderConfiguration {}
