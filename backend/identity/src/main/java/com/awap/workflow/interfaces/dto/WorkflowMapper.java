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

  /**
   * Version snapshots need the definition's current version to mark the active one, so this is
   * mapped explicitly instead of relying on a generated entity-only mapper.
   */
  default WorkflowVersionResponse toVersionResponse(WorkflowVersion version, int currentVersion) {
    boolean archived = version.isDeleted();
    boolean current =
        !archived
            && version.getStatus() == WorkflowStatus.PUBLISHED
            && version.getVersion() == currentVersion;
    return new WorkflowVersionResponse(
        version.getId(),
        version.getVersion(),
        version.getDefinitionJson(),
        statusToString(version.getStatus()),
        archived,
        current,
        version.getCreatedAt());
  }

  @Named("statusToString")
  default String statusToString(WorkflowStatus status) {
    return status == null ? null : status.name();
  }
}
