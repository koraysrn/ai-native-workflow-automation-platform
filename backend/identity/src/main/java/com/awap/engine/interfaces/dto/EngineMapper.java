package com.awap.engine.interfaces.dto;

import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** Maps workflow engine entities to their public representations. */
@Mapper(componentModel = "spring")
public interface EngineMapper {

  @Mapping(target = "state", source = "state", qualifiedByName = "stateToString")
  WorkflowInstanceResponse toResponse(WorkflowInstance instance);

  ExecutionLogResponse toLogResponse(ExecutionLog log);

  @Named("stateToString")
  default String stateToString(WorkflowState state) {
    return state == null ? null : state.name();
  }
}
