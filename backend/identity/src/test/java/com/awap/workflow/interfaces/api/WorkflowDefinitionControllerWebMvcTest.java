package com.awap.workflow.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.workflow.application.ExpressionValidationResult;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.application.WorkflowExpressionValidationService;
import com.awap.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.awap.workflow.interfaces.dto.WorkflowVersionResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class WorkflowDefinitionControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private WorkflowDefinitionService service;
  @MockitoBean private WorkflowExpressionValidationService expressionValidationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    TenantContext.set(UUID.randomUUID(), UUID.randomUUID(), "MANAGER");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void listRequiresInstanceRead() throws Exception {
    when(service.list(any(UUID.class))).thenReturn(List.of());
    mockMvc
        .perform(
            get("/api/v1/workflow-definitions")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk());
  }

  @Test
  void versionsExposePublishStatusVersionAndArchiveFlag() throws Exception {
    UUID definitionId = UUID.randomUUID();
    when(service.versions(any(UUID.class), any(UUID.class)))
        .thenReturn(
            List.of(
                new WorkflowVersionResponse(
                    UUID.randomUUID(),
                    2,
                    "{}",
                    "PUBLISHED",
                    false,
                    true,
                    Instant.parse("2026-01-01T00:00:00Z"))));

    mockMvc
        .perform(
            get("/api/v1/workflow-definitions/{id}/versions", definitionId)
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
        .andExpect(jsonPath("$[0].archived").value(false))
        .andExpect(jsonPath("$[0].current").value(true));
  }

  @Test
  void listUnauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/workflow-definitions")).andExpect(status().isUnauthorized());
  }

  @Test
  void validateExpressionReturnsFieldLevelErrors() throws Exception {
    when(expressionValidationService.validate("1 +"))
        .thenReturn(ExpressionValidationResult.invalid("expression", "Expression is invalid."));

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"expression\":\"1 +\"}")
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_CREATE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.errors[0].field").value("expression"))
        .andExpect(jsonPath("$.errors[0].message").value("Expression is invalid."));
  }

  @Test
  void validateExpressionAcceptsBlankConditionAsUnconditional() throws Exception {
    when(expressionValidationService.validate("   "))
        .thenReturn(ExpressionValidationResult.validResult());

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"expression\":\"   \"}")
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_CREATE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.errors").isEmpty());
  }

  @Test
  void validateExpressionRequiresWorkflowCreate() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"expression\":\"#amount > 1\"}")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void validateExpressionRequiresAuthentication() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"expression\":\"#amount > 1\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validateExpressionRejectsAMissingExpressionField() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{}")
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_CREATE"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void validateExpressionRequiresATenantContext() throws Exception {
    TenantContext.clear();

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/validate-expression")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"expression\":\"#amount > 1\"}")
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_CREATE"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createReturnsTheCreatedDraft() throws Exception {
    WorkflowDefinitionResponse response =
        new WorkflowDefinitionResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Approval", null, "DRAFT", 1);
    when(service.create(any(UUID.class), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions")
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Approval\",\"definitionJson\":\"{}\"}")
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_CREATE"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Approval"))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void getReturnsTheDefinitionForTheCurrentTenant() throws Exception {
    WorkflowDefinitionResponse response =
        new WorkflowDefinitionResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Approval", null, "PUBLISHED", 2);
    when(service.get(any(UUID.class), eq(response.id()))).thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/workflow-definitions/{id}", response.id())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(2))
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  @Test
  void publishDelegatesToTheService() throws Exception {
    WorkflowDefinitionResponse response =
        new WorkflowDefinitionResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Approval", null, "PUBLISHED", 1);
    when(service.publish(any(UUID.class), eq(response.id()))).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/{id}/publish", response.id())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_PUBLISH"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  @Test
  void rollbackDelegatesToTheServiceWithTheTargetVersion() throws Exception {
    WorkflowDefinitionResponse response =
        new WorkflowDefinitionResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Approval", null, "PUBLISHED", 1);
    when(service.rollback(any(UUID.class), eq(response.id()), eq(1))).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/{id}/rollback/{version}", response.id(), 1)
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_PUBLISH"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(1));
  }

  @Test
  void archiveVersionReturnsNoContent() throws Exception {
    UUID definitionId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/workflow-definitions/{id}/versions/{version}/archive", definitionId, 1)
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("WORKFLOW_PUBLISH"))))
        .andExpect(status().isNoContent());

    verify(service).archiveVersion(any(UUID.class), eq(definitionId), eq(1));
  }
}
