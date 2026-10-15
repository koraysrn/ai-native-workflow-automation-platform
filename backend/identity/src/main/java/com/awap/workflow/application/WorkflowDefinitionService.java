package com.awap.workflow.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVersion;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionRequest;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.awap.workflow.interfaces.dto.WorkflowVersionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow definition use cases: draft, publish, rollback and version listing. */
@Service
public class WorkflowDefinitionService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final ObjectMapper objectMapper;

  public WorkflowDefinitionService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      ObjectMapper objectMapper) {
    this.definitionRepository = definitionRepository;
    this.versionRepository = versionRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public WorkflowDefinitionResponse create(UUID tenantId, WorkflowDefinitionRequest request) {
    validateDefinition(request.definitionJson());
    WorkflowDefinition definition =
        new WorkflowDefinition(tenantId, request.name(), request.description());
    definitionRepository.save(definition);
    WorkflowVersion version =
        new WorkflowVersion(
            tenantId, definition.getId(), 1, request.definitionJson(), WorkflowStatus.DRAFT);
    versionRepository.save(version);
    return toResponse(definition);
  }

  public List<WorkflowDefinitionResponse> list(UUID tenantId) {
    return definitionRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(this::toResponse)
        .toList();
  }

  public WorkflowDefinitionResponse get(UUID tenantId, UUID id) {
    return toResponse(find(tenantId, id));
  }

  @Transactional
  public WorkflowDefinitionResponse publish(UUID tenantId, UUID id) {
    WorkflowDefinition definition = find(tenantId, id);
    WorkflowVersion latest =
        versionRepository
            .findTopByWorkflowIdOrderByVersionDesc(id)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Workflow version not found."));
    latest.publish();
    definition.publish(latest.getVersion());
    return toResponse(definition);
  }

  @Transactional
  public WorkflowDefinitionResponse rollback(UUID tenantId, UUID id, int targetVersion) {
    WorkflowDefinition definition = find(tenantId, id);
    versionRepository
        .findByWorkflowIdAndVersion(id, targetVersion)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Target version not found."));
    definition.rollbackTo(targetVersion);
    return toResponse(definition);
  }

  public List<WorkflowVersionResponse> versions(UUID tenantId, UUID id) {
    find(tenantId, id);
    return versionRepository.findByWorkflowIdOrderByVersionDesc(id).stream()
        .map(this::toVersionResponse)
        .toList();
  }

  private WorkflowDefinition find(UUID tenantId, UUID id) {
    return definitionRepository
        .findByIdAndTenantIdAndDeletedFalse(id, tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Workflow definition not found."));
  }

  private void validateDefinition(String json) {
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode nodes = root.get("nodes");
      if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
        throw invalidDefinition();
      }
      for (JsonNode node : nodes) {
        String type = node.path("type").asText(null);
        if (type == null || !isSupportedType(type)) {
          throw invalidDefinition();
        }
      }
    } catch (JsonProcessingException ex) {
      throw invalidDefinition();
    }
  }

  private boolean isSupportedType(String type) {
    for (NodeType nodeType : NodeType.values()) {
      if (nodeType.name().equals(type)) {
        return true;
      }
    }
    return false;
  }

  private AwapException invalidDefinition() {
    return new AwapException(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.BAD_REQUEST,
        "Workflow definition JSON is invalid.");
  }

  private WorkflowDefinitionResponse toResponse(WorkflowDefinition definition) {
    return new WorkflowDefinitionResponse(
        definition.getId(),
        definition.getTenantId(),
        definition.getName(),
        definition.getDescription(),
        definition.getStatus().name(),
        definition.getCurrentVersion());
  }

  private WorkflowVersionResponse toVersionResponse(WorkflowVersion version) {
    return new WorkflowVersionResponse(
        version.getId(),
        version.getVersion(),
        version.getDefinitionJson(),
        version.getStatus().name(),
        version.getCreatedAt());
  }
}
