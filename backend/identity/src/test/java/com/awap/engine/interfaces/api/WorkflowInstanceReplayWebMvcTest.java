package com.awap.engine.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.application.ReplayService;
import com.awap.engine.application.WorkflowEngineService;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.ReplayDivergenceType;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.interfaces.dto.ReplayDivergenceView;
import com.awap.engine.interfaces.dto.ReplayReport;
import com.awap.engine.interfaces.dto.ReplayStateView;
import com.awap.engine.interfaces.dto.WorkflowInstanceResponse;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class WorkflowInstanceReplayWebMvcTest {

  private static final UUID INSTANCE = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private WebApplicationContext context;
  @MockitoBean private WorkflowEngineService engineService;
  @MockitoBean private ReplayService replayService;

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

  private ReplayReport report(String result, List<ReplayDivergenceView> divergences) {
    return new ReplayReport(
        INSTANCE,
        true,
        result,
        "MATCHED".equals(result) ? Optional.empty() : Optional.of("AWAP-ENG-0004"),
        new ReplayStateView(
            INSTANCE,
            WorkflowState.COMPLETED.name(),
            Optional.of("end"),
            Map.of("end", NodeExecutionStatus.COMPLETED.name()),
            Map.of(),
            Map.of(),
            Map.of("end", "len=2;sha256=abc;keys=ok"),
            8),
        divergences,
        8,
        1,
        12L,
        "a".repeat(64));
  }

  @Test
  void dryRunReturnsTheProjectionReport() throws Exception {
    when(replayService.dryRun(any(UUID.class), eq(INSTANCE), any()))
        .thenReturn(report("MATCHED", List.of()));

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/{id}/replay", INSTANCE)
                .queryParam("dryRun", "true")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE")))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.result").value("MATCHED"))
        .andExpect(jsonPath("$.projection.instanceState").value("COMPLETED"))
        .andExpect(jsonPath("$.logRows").value(8))
        .andExpect(jsonPath("$.stateHash").value("a".repeat(64)));
  }

  @Test
  void realReplayReturnsTheInstance() throws Exception {
    WorkflowInstanceResponse response =
        new WorkflowInstanceResponse(
            INSTANCE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "RUNNING",
            "{}",
            null,
            Instant.parse("2026-01-01T00:00:00Z"));
    when(replayService.replay(any(UUID.class), eq(INSTANCE), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/{id}/replay", INSTANCE)
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE")))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(INSTANCE.toString()))
        .andExpect(jsonPath("$.state").value("RUNNING"));
  }

  @Test
  void divergenceIsReportedAsProblemDetail409() throws Exception {
    when(replayService.replay(any(UUID.class), eq(INSTANCE), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.REPLAY_DIVERGENCE,
                HttpStatus.CONFLICT,
                "Replay diverged from the persisted state: MISSING_NODE_COMPLETION@approval"));

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/{id}/replay", INSTANCE)
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE")))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("AWAP-ENG-0004"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void concurrentReplayIsReportedAsProblemDetail409() throws Exception {
    when(replayService.replay(any(UUID.class), eq(INSTANCE), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.REPLAY_CONFLICT,
                HttpStatus.CONFLICT,
                "A replay is already running for this workflow instance."));

    mockMvc
        .perform(
            post("/api/v1/workflow-instances/{id}/replay", INSTANCE)
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE")))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("AWAP-ENG-0005"));
  }

  @Test
  void dryRunRequiresInstanceManage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflow-instances/{id}/replay", INSTANCE)
                .queryParam("dryRun", "true")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ")))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void divergenceTypeEnumIsExposedForContractTests() {
    org.assertj.core.api.Assertions.assertThat(ReplayDivergenceType.values())
        .extracting(Enum::name)
        .contains("UNKNOWN_ACTION", "MISSING_NODE_COMPLETION", "MALFORMED_PAYLOAD");
  }
}
