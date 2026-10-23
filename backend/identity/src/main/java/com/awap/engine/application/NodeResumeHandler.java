package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;

/** Resumes a delayed node execution whose resume instant has elapsed. */
public interface NodeResumeHandler {

  void handle(NodeExecution execution);
}
