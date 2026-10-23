package com.awap.engine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the delay scheduler polls due node executions and delegates them to the handler. */
@ExtendWith(MockitoExtension.class)
class DelaySchedulerTest {

  @Mock private NodeExecutionRepository repository;
  @Mock private NodeResumeHandler handler;

  @Test
  void delegatesDueExecutionsToHandler() {
    DelayScheduler scheduler = new DelayScheduler(repository, handler);
    NodeExecution due = new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "DELAY");
    due.scheduleResume(Instant.now().minusSeconds(5));
    when(repository.findByResumeAtNotNullAndResumeAtBeforeOrderByResumeAtAsc(any()))
        .thenReturn(List.of(due));

    scheduler.resumeDueNodes();

    verify(handler).handle(due);
  }

  @Test
  void doesNothingWhenNoExecutionsAreDue() {
    DelayScheduler scheduler = new DelayScheduler(repository, handler);
    when(repository.findByResumeAtNotNullAndResumeAtBeforeOrderByResumeAtAsc(any()))
        .thenReturn(List.of());

    scheduler.resumeDueNodes();

    verify(handler, never()).handle(any());
  }
}
