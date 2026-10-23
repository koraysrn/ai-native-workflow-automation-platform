package com.awap.task.interfaces.dto;

import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** Maps task entities to their public representation. */
@Mapper(componentModel = "spring")
public interface TaskMapper {

  @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
  TaskResponse toResponse(Task task);

  @Named("statusToString")
  default String statusToString(TaskStatus status) {
    return status == null ? null : status.name();
  }
}
