package com.awap.file.application;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for fan-out of scan-status events.
 *
 * <p>The scan scheduler must never block on a slow SSE consumer, so publishing hands the event to
 * this bounded pool. When the queue is full (many stalled clients) the event is discarded instead
 * of applying back-pressure to scanning; polling fallback in the UI reconciles the missed update.
 */
@Configuration
public class FileScanEventConfiguration {

  /** Maximum number of queued deliveries before the oldest slow-client updates are dropped. */
  static final int QUEUE_CAPACITY = 1024;

  @Bean(name = "fileScanEventExecutor")
  public ThreadPoolTaskExecutor fileScanEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("file-scan-event-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
  }
}
