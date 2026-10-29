package com.awap.webhook.interfaces.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.webhook.application.WebhookDeliveryService;
import com.awap.webhook.domain.WebhookDelivery;
import com.awap.webhook.domain.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

/** Tenant-scoped read API: authorization, projection safety and RFC 7807 validation errors. */
@SpringBootTest(classes = IdentityApplication.class)
class WebhookDeliveryControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private WebhookDeliveryService deliveryService;

  private MockMvc mockMvc;
  private final UUID tenantId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    TenantContext.set(tenantId, UUID.randomUUID(), "MANAGER");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private WebhookDelivery deadDelivery() {
    WebhookDelivery delivery =
        WebhookDelivery.pending(
            tenantId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "hook",
            "https://hooks.example.com/path?secret=abc",
            "{\"secret\":\"payload\"}",
            "instance:hook:execution",
            3,
            3,
            500,
            "target returned status 500.",
            Instant.parse("2026-05-01T10:00:00Z"),
            Instant.parse("2026-05-01T10:00:24Z"),
            Instant.parse("2026-05-08T10:00:00Z"));
    delivery.markDead(500, "target returned status 500.", Instant.parse("2026-05-01T10:01:00Z"));
    return delivery;
  }

  @Test
  void listReturnsTheTenantsDeadRowsWithoutPayloadOrQueryString() throws Exception {
    when(deliveryService.list(eq(tenantId), eq(Optional.of(WebhookDeliveryStatus.DEAD)), eq(20)))
        .thenReturn(List.of(deadDelivery()));

    mockMvc
        .perform(
            get("/api/v1/webhook-deliveries?status=dead&limit=20")
                .with(user("ops").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("DEAD"))
        .andExpect(jsonPath("$[0].nodeId").value("hook"))
        .andExpect(jsonPath("$[0].attempts").value(3))
        .andExpect(jsonPath("$[0].maxAttempts").value(3))
        .andExpect(jsonPath("$[0].lastStatusCode").value(500))
        .andExpect(jsonPath("$[0].lastError").value("target returned status 500."))
        .andExpect(jsonPath("$[0].targetUrl").value("https://hooks.example.com/path"))
        .andExpect(jsonPath("$[0].payloadJson").doesNotExist());

    verify(deliveryService).list(tenantId, Optional.of(WebhookDeliveryStatus.DEAD), 20);
  }

  @Test
  void listWithoutStatusFilterUsesAnEmptyFilter() throws Exception {
    when(deliveryService.list(eq(tenantId), eq(Optional.empty()), anyInt())).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/webhook-deliveries")
                .with(user("ops").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unauthenticatedRequestIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/webhook-deliveries")).andExpect(status().isUnauthorized());

    verify(deliveryService, never()).list(eq(tenantId), eq(Optional.empty()), anyInt());
  }

  @Test
  void missingAuthorityIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/webhook-deliveries")
                .with(user("viewer").authorities(new SimpleGrantedAuthority("TASK_READ"))))
        .andExpect(status().isForbidden());

    verify(deliveryService, never()).list(eq(tenantId), eq(Optional.empty()), anyInt());
  }

  @Test
  void unknownStatusIsAProblemDetailValidationError() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/webhook-deliveries?status=exploded")
                .with(user("ops").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Request validation failed."))
        .andExpect(jsonPath("$.code").value("AWAP-COMMON-0002"));

    verify(deliveryService, never()).list(eq(tenantId), eq(Optional.empty()), anyInt());
  }

  @Test
  void tenantContextIsRequired() throws Exception {
    TenantContext.clear();

    mockMvc
        .perform(
            get("/api/v1/webhook-deliveries")
                .with(user("ops").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isUnauthorized());
  }
}
