package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import org.springframework.stereotype.Component;

/** Default handler used until the engine implements the DELAY node resume logic. */
@Component
public class NoopNodeResumeHandler implements NodeResumeHandler {

  @Override
  public void handle(NodeExecution execution) {
    // Faz 1: the workflow engine resumes the node from its recorded state.
  }
}
