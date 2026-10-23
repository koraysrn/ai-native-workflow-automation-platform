package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls node executions whose resume instant has elapsed and delegates to the resume handler. */
@Component
public class DelayScheduler {

  private final NodeExecutionRepository repository;
  private final NodeResumeHandler handler;

  public DelayScheduler(NodeExecutionRepository repository, NodeResumeHandler handler) {
    this.repository = repository;
    this.handler = handler;
  }

  @Scheduled(fixedDelay = 5000)
  public void resumeDueNodes() {
    repository
        .findByResumeAtNotNullAndResumeAtBeforeOrderByResumeAtAsc(Instant.now())
        .forEach(this::resume);
  }

  private void resume(NodeExecution execution) {
    handler.handle(execution);
  }
}
