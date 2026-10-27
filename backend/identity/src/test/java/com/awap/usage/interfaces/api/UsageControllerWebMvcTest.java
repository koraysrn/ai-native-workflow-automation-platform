package com.awap.usage.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.usage.application.UsageQueryService;
import com.awap.usage.domain.UsageMetric;
import com.awap.usage.interfaces.dto.UsageMetricResponse;
import com.awap.usage.interfaces.dto.UsageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class UsageControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private UsageQueryService usageQueryService;

  private final UUID tenantId = UUID.randomUUID();

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  @Test
  void usageEndpointReturnsPerMetricReport() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = LocalDate.of(2026, 1, 2);
    when(usageQueryService.report(
            eq(tenantId), eq(tenantId), eq("TENANT_ADMIN"), eq(from), eq(to), eq(Optional.empty())))
        .thenReturn(
            new UsageResponse(
                tenantId,
                from,
                to,
                List.of(
                    new UsageMetricResponse(
                        "workflow_executions", "COUNT", 3L, 1000L, 997L, List.of()))));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId)
                .param("from", "2026-01-01")
                .param("to", "2026-01-02")
                .with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.metrics[0].metric").value("workflow_executions"))
        .andExpect(jsonPath("$.metrics[0].total").value(3))
        .andExpect(jsonPath("$.metrics[0].limit").value(1000))
        .andExpect(jsonPath("$.metrics[0].remaining").value(997));
  }

  @Test
  void unknownMetricKeyIsRejectedWith400() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId)
                .param("metric", "tenant.email")
                .with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.USAGE_METRIC_UNKNOWN.code()));
  }

  @Test
  void invertedWindowIsRejectedWith400() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId)
                .param("from", "2026-02-01")
                .param("to", "2026-01-01")
                .with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
  }

  @Test
  void deletedTenantPropagates404() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");
    when(usageQueryService.report(eq(tenantId), eq(tenantId), any(), any(), any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.TENANT_NOT_FOUND, HttpStatus.NOT_FOUND, "The tenant was not found."));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId).with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.TENANT_NOT_FOUND.code()));
  }

  @Test
  void quotaExceededPropagates429() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");
    when(usageQueryService.report(eq(tenantId), eq(tenantId), any(), any(), any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.QUOTA_EXCEEDED,
                HttpStatus.TOO_MANY_REQUESTS,
                "Daily quota of 1000 workflow_executions exceeded."));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId).with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(ErrorCode.QUOTA_EXCEEDED.code()));
  }

  @Test
  void metricQueryParameterIsParsedAndForwarded() throws Exception {
    TenantContext.set(tenantId, UUID.randomUUID(), "TENANT_ADMIN");
    when(usageQueryService.report(
            eq(tenantId),
            eq(tenantId),
            eq("TENANT_ADMIN"),
            any(),
            any(),
            eq(Optional.of(UsageMetric.AI_CALL))))
        .thenReturn(
            new UsageResponse(
                tenantId, LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1), List.of()));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            get("/api/v1/tenants/{id}/usage", tenantId)
                .param("metric", "ai_calls")
                .with(user("admin").roles("TENANT_ADMIN")))
        .andExpect(status().isOk());
  }
}
