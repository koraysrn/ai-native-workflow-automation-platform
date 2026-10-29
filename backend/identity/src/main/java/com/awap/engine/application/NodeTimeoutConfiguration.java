package com.awap.engine.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the node timeout bounds to configuration and owns the bounded timeout executor.
 *
 * <p>Defaults: 30s per node, 1 hour maximum, 8 worker threads and a queue of 64. Invalid values
 * fail fast in {@link NodeTimeoutSettings}.
 */
@Configuration
public class NodeTimeoutConfiguration {

  @Bean
  public NodeTimeoutSettings nodeTimeoutSettings(
      @Value("${awap.engine.node-timeout.default-seconds:30}") int defaultSeconds,
      @Value("${awap.engine.node-timeout.max-seconds:3600}") int maxSeconds,
      @Value("${awap.engine.node-timeout.worker-threads:8}") int workerThreads,
      @Value("${awap.engine.node-timeout.queue-capacity:64}") int queueCapacity) {
    return new NodeTimeoutSettings(defaultSeconds, maxSeconds, workerThreads, queueCapacity);
  }

  @Bean(destroyMethod = "shutdown")
  public NodeExecutionWatchdog nodeExecutionWatchdog(
      NodeTimeoutSettings settings, NodeTimeoutMetrics metrics) {
    return new NodeExecutionWatchdog(settings, metrics);
  }
}
