package com.awap.form.interfaces.dto;

import com.awap.form.domain.FormDefinition;
import com.awap.form.domain.FormSubmission;
import org.mapstruct.Mapper;

/** Maps form entities to their public representations. */
@Mapper(componentModel = "spring")
public interface FormMapper {

  FormDefinitionResponse toResponse(FormDefinition definition);

  FormSubmissionResponse toResponse(FormSubmission submission);
}
