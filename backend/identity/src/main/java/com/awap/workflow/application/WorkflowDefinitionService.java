package com.awap.workflow.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.application.QuotaService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.awap.workflow.domain.TransitionDefinition;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVariable;
import com.awap.workflow.domain.WorkflowVersion;
import com.awap.workflow.infrastructure.NodeDefinitionRepository;
import com.awap.workflow.infrastructure.TransitionDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowDefinitionRepository;
import com.awap.workflow.infrastructure.WorkflowVariableRepository;
import com.awap.workflow.infrastructure.WorkflowVersionRepository;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionRequest;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.awap.workflow.interfaces.dto.WorkflowMapper;
import com.awap.workflow.interfaces.dto.WorkflowVersionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow definition use cases: draft, publish, rollback and version listing. */
@Service
public class WorkflowDefinitionService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeDefinitionRepository;
  private final TransitionDefinitionRepository transitionDefinitionRepository;
  private final WorkflowVariableRepository workflowVariableRepository;
  private final ObjectMapper objectMapper;
  private final SpelExpressionService spelExpressionService;
  private final QuotaService quotaService;
  private final WorkflowMapper workflowMapper;

  public WorkflowDefinitionService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeDefinitionRepository,
      TransitionDefinitionRepository transitionDefinitionRepository,
      WorkflowVariableRepository workflowVariableRepository,
      ObjectMapper objectMapper,
      SpelExpressionService spelExpressionService,
      QuotaService quotaService,
      WorkflowMapper workflowMapper) {
    this.definitionRepository = definitionRepository;
    this.versionRepository = versionRepository;
    this.nodeDefinitionRepository = nodeDefinitionRepository;
    this.transitionDefinitionRepository = transitionDefinitionRepository;
    this.workflowVariableRepository = workflowVariableRepository;
    this.objectMapper = objectMapper;
    this.spelExpressionService = spelExpressionService;
    this.quotaService = quotaService;
    this.workflowMapper = workflowMapper;
  }

  @Transactional
  public WorkflowDefinitionResponse create(UUID tenantId, WorkflowDefinitionRequest request) {
    quotaService.assertCanCreateWorkflow(tenantId);
    validateDefinition(request.definitionJson());
    WorkflowDefinition definition =
        new WorkflowDefinition(tenantId, request.name(), request.description());
    definitionRepository.save(definition);
    WorkflowVersion version =
        new WorkflowVersion(
            tenantId, definition.getId(), 1, request.definitionJson(), WorkflowStatus.DRAFT);
    versionRepository.save(version);
    persistGraph(tenantId, version.getId(), request.definitionJson());
    return workflowMapper.toResponse(definition);
  }

  public List<WorkflowDefinitionResponse> list(UUID tenantId) {
    return definitionRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
        .map(workflowMapper::toResponse)
        .toList();
  }

  public WorkflowDefinitionResponse get(UUID tenantId, UUID id) {
    return workflowMapper.toResponse(find(tenantId, id));
  }

  public WorkflowDefinition getPublished(UUID tenantId, UUID id) {
    WorkflowDefinition definition = find(tenantId, id);
    if (definition.getStatus() != WorkflowStatus.PUBLISHED) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Workflow definition is not published.");
    }
    return definition;
  }

  /**
   * Resolves a specifically requested version snapshot. Only PUBLISHED, non-archived versions owned
   * by the tenant are selectable, which lets long-running SUB_PROCESS parents pin an older version
   * even after the definition is republished.
   *
   * @throws AwapException 400 for a non-positive version, 404 when the definition or version is
   *     missing for the tenant, 409 when the version is unpublished or archived
   */
  @Transactional(readOnly = true)
  public WorkflowVersion getPublishedVersion(UUID tenantId, UUID definitionId, int version) {
    if (version <= 0) {
      throw invalidVersion(version);
    }
    find(tenantId, definitionId);
    return requirePublishedVersion(tenantId, definitionId, version);
  }

  /**
   * Resolves the version the definition currently points at. Fails with 409 when the definition was
   * never published, so SUB_PROCESS never starts a child against an unpublished definition.
   */
  @Transactional(readOnly = true)
  public WorkflowVersion getCurrentPublishedVersion(UUID tenantId, UUID definitionId) {
    WorkflowDefinition definition = find(tenantId, definitionId);
    if (definition.getStatus() != WorkflowStatus.PUBLISHED || definition.getCurrentVersion() <= 0) {
      throw new AwapException(
          ErrorCode.WORKFLOW_VERSION_NOT_PUBLISHED,
          HttpStatus.CONFLICT,
          "Workflow definition '" + definitionId + "' has no published version.");
    }
    return requirePublishedVersion(tenantId, definitionId, definition.getCurrentVersion());
  }

  private WorkflowVersion requirePublishedVersion(UUID tenantId, UUID definitionId, int version) {
    WorkflowVersion target =
        versionRepository
            .findByWorkflowIdAndVersionAndTenantId(definitionId, version, tenantId)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.WORKFLOW_VERSION_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Workflow version "
                            + version
                            + " was not found for definition '"
                            + definitionId
                            + "'."));
    if (target.isDeleted()) {
      throw new AwapException(
          ErrorCode.WORKFLOW_VERSION_ARCHIVED,
          HttpStatus.CONFLICT,
          "Workflow version " + version + " is archived and cannot be selected.");
    }
    if (target.getStatus() != WorkflowStatus.PUBLISHED) {
      throw new AwapException(
          ErrorCode.WORKFLOW_VERSION_NOT_PUBLISHED,
          HttpStatus.CONFLICT,
          "Workflow version " + version + " is not published.");
    }
    return target;
  }

  private AwapException invalidVersion(int version) {
    return new AwapException(
        ErrorCode.WORKFLOW_VERSION_INVALID,
        HttpStatus.BAD_REQUEST,
        "Workflow version must be a positive integer, but was " + version + ".");
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
    return workflowMapper.toResponse(definition);
  }

  @Transactional
  public WorkflowDefinitionResponse rollback(UUID tenantId, UUID id, int targetVersion) {
    WorkflowDefinition definition = find(tenantId, id);
    WorkflowVersion target =
        versionRepository
            .findByWorkflowIdAndVersion(id, targetVersion)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Target version not found."));
    target.publish();
    definition.rollbackTo(targetVersion);
    return workflowMapper.toResponse(definition);
  }

  public List<WorkflowVersionResponse> versions(UUID tenantId, UUID id) {
    WorkflowDefinition definition = find(tenantId, id);
    int currentVersion = definition.getCurrentVersion();
    return versionRepository.findByWorkflowIdOrderByVersionDesc(id).stream()
        .map(version -> workflowMapper.toVersionResponse(version, currentVersion))
        .toList();
  }

  /** Facade for the engine: returns the node graph of a published version. */
  public List<NodeDefinition> nodesOf(UUID workflowDefinitionId, int version) {
    UUID versionId = resolveVersionId(workflowDefinitionId, version);
    return nodeDefinitionRepository.findByWorkflowVersionIdOrderByOrderIndexAsc(versionId);
  }

  /** Facade for the engine: returns the transitions of a published version. */
  public List<TransitionDefinition> transitionsOf(UUID workflowDefinitionId, int version) {
    UUID versionId = resolveVersionId(workflowDefinitionId, version);
    return transitionDefinitionRepository.findByWorkflowVersionId(versionId);
  }

  private UUID resolveVersionId(UUID workflowDefinitionId, int version) {
    WorkflowVersion target =
        versionRepository
            .findByWorkflowIdAndVersion(workflowDefinitionId, version)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Workflow version not found."));
    return target.getId();
  }

  @Transactional
  public void archiveVersion(UUID tenantId, UUID id, int version) {
    find(tenantId, id);
    WorkflowVersion target =
        versionRepository
            .findByWorkflowIdAndVersion(id, version)
            .orElseThrow(
                () ->
                    new AwapException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Target version not found."));
    target.archive();
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
      Set<String> nodeIds = new HashSet<>();
      for (JsonNode node : nodes) {
        String type = node.path("type").asText(null);
        if (type == null || !isSupportedType(type)) {
          throw invalidDefinition();
        }
        String nodeId = node.path("id").asText(null);
        if (nodeId == null || nodeId.isBlank()) {
          throw invalidDefinition();
        }
        nodeIds.add(nodeId);
        String expression = node.path("expression").asText(null);
        if (expression != null) {
          spelExpressionService.validate(expression);
        }
        String condition = node.path("condition").asText(null);
        if (condition != null) {
          spelExpressionService.validate(condition);
        }
      }

      JsonNode transitions = root.get("transitions");
      if (transitions == null) {
        transitions = root.get("edges");
      }
      if (transitions != null) {
        if (!transitions.isArray()) {
          throw invalidDefinition();
        }
        for (JsonNode transition : transitions) {
          String from = transition.path("from").asText(null);
          String to = transition.path("to").asText(null);
          if (from == null || to == null || !nodeIds.contains(from) || !nodeIds.contains(to)) {
            throw invalidDefinition();
          }
          String condition = transition.path("condition").asText(null);
          if (condition != null) {
            spelExpressionService.validate(condition);
          }
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

  private void persistGraph(UUID tenantId, UUID workflowVersionId, String definitionJson) {
    try {
      JsonNode root = objectMapper.readTree(definitionJson);
      JsonNode nodes = root.get("nodes");
      int orderIndex = 0;
      for (JsonNode node : nodes) {
        String nodeId = node.path("id").asText(null);
        String type = node.path("type").asText(null);
        if (nodeId == null || type == null) {
          continue;
        }
        nodeDefinitionRepository.save(
            new NodeDefinition(
                tenantId,
                workflowVersionId,
                nodeId,
                NodeType.valueOf(type),
                node.path("name").asText(nodeId),
                node.has("config") ? node.get("config").toString() : null,
                orderIndex++));
      }

      JsonNode transitions = root.get("transitions");
      if (transitions == null || !transitions.isArray()) {
        transitions = root.get("edges");
      }
      if (transitions != null && transitions.isArray()) {
        for (JsonNode transition : transitions) {
          String from = transition.path("from").asText(null);
          String to = transition.path("to").asText(null);
          if (from == null || to == null) {
            continue;
          }
          transitionDefinitionRepository.save(
              new TransitionDefinition(
                  tenantId,
                  workflowVersionId,
                  from,
                  to,
                  transition.path("condition").asText(null)));
        }
      }

      JsonNode variables = root.get("variables");
      if (variables != null && variables.isArray()) {
        for (JsonNode variable : variables) {
          String name = variable.path("name").asText(null);
          if (name == null) {
            continue;
          }
          workflowVariableRepository.save(
              new WorkflowVariable(
                  tenantId,
                  workflowVersionId,
                  name,
                  variable.path("type").asText("STRING"),
                  variable.path("default").asText(null)));
        }
      }
    } catch (JsonProcessingException ex) {
      throw invalidDefinition();
    }
  }

  private AwapException invalidDefinition() {
    return new AwapException(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.BAD_REQUEST,
        "Workflow definition JSON is invalid.");
  }
}
