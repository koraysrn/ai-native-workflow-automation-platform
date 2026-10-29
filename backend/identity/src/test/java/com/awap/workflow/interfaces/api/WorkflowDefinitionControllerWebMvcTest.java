package com.awap.workflow.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.workflow.application.WorkflowDefinitionService;
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
}
