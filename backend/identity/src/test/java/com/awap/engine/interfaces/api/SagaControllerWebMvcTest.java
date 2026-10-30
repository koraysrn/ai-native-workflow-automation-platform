package com.awap.engine.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.engine.application.SagaService;
import com.awap.engine.interfaces.dto.SagaResponse;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
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

/** API surface and authorization of the saga inspection/manual-intervention endpoints. */
@SpringBootTest(classes = IdentityApplication.class)
class SagaControllerWebMvcTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID INSTANCE_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;
  @MockitoBean private SagaService sagaService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    TenantContext.set(TENANT, UUID.randomUUID(), "MANAGER");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void getReturnsTheSagaForInstanceRead() throws Exception {
    when(sagaService.get(any(UUID.class), eq(INSTANCE_ID))).thenReturn(response("COMPENSATING"));

    mockMvc
        .perform(
            get("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowInstanceId").value(INSTANCE_ID.toString()))
        .andExpect(jsonPath("$.status").value("COMPENSATING"));
  }

  @Test
  void getRequiresAuthentication() throws Exception {
    mockMvc
        .perform(get("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedRequestWithoutTenantContextReturnsUnauthorized() throws Exception {
    TenantContext.clear();

    mockMvc
        .perform(
            get("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void manualCompensationRequiresInstanceManage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga/compensate")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void manualCompensationIsAllowedForInstanceManage() throws Exception {
    when(sagaService.manualCompensate(any(UUID.class), eq(INSTANCE_ID)))
        .thenReturn(response("COMPENSATED"));

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga/compensate")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPENSATED"));
  }

  @Test
  void stepRetryDelegatesToTheSagaService() throws Exception {
    UUID stepId = UUID.randomUUID();
    when(sagaService.retryStep(any(UUID.class), eq(INSTANCE_ID), eq(stepId)))
        .thenReturn(response("COMPENSATING"));

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/" + INSTANCE_ID + "/saga/steps/" + stepId + "/retry")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isOk());

    verify(sagaService).retryStep(TENANT, INSTANCE_ID, stepId);
  }

  private SagaResponse response(String status) {
    Instant now = Instant.parse("2026-10-30T10:00:00Z");
    return new SagaResponse(
        UUID.randomUUID(),
        TENANT,
        INSTANCE_ID,
        "workflow:" + INSTANCE_ID,
        status,
        null,
        now,
        null,
        now,
        now,
        List.of());
  }
}
