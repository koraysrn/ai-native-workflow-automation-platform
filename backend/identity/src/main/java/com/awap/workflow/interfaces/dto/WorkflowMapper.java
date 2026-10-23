package com.awap.workflow.interfaces.dto;

import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVersion;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** Maps workflow entities to their public representations. */
@Mapper(componentModel = "spring")
public interface WorkflowMapper {

  @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
  WorkflowDefinitionResponse toResponse(WorkflowDefinition definition);

  @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
  WorkflowVersionResponse toResponse(WorkflowVersion version);

  @Named("statusToString")
  default String statusToString(WorkflowStatus status) {
    return status == null ? null : status.name();
  }
}
