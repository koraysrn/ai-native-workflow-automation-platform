package com.awap.engine.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the replay engine configuration properties. */
@Configuration
@EnableConfigurationProperties(ReplaySettings.class)
public class ReplayConfiguration {}
