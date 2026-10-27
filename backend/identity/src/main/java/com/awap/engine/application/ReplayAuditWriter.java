package com.awap.engine.application;

import com.awap.engine.domain.ReplayRun;
import com.awap.engine.infrastructure.ReplayRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the replay audit row in its own transaction so a divergence (which aborts the replay) and
 * its evidence are both durable.
 */
@Service
public class ReplayAuditWriter {

  private final ReplayRunRepository repository;

  public ReplayAuditWriter(ReplayRunRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReplayRun record(ReplayRun run) {
    return repository.save(run);
  }
}
