package com.awap.form.interfaces.dto;

import com.awap.form.domain.FormDefinition;
import com.awap.form.domain.FormSubmission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps form entities to their public representations. */
@Mapper(componentModel = "spring")
public interface FormMapper {

  FormDefinitionResponse toResponse(FormDefinition definition);

  /** File metadata is looked up from the file module, so the mapper starts with an empty list. */
  @Mapping(target = "files", expression = "java(java.util.List.<FormSubmissionFileResponse>of())")
  FormSubmissionResponse toResponse(FormSubmission submission);
}
