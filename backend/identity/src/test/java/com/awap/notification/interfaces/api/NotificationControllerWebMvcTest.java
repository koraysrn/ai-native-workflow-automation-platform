package com.awap.notification.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.notification.application.NotificationReadService;
import com.awap.notification.domain.NotificationChannel;
import com.awap.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
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
class NotificationControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private NotificationReadService service;

  private MockMvc mockMvc;
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    tenantId = UUID.randomUUID();
    TenantContext.set(tenantId, UUID.randomUUID(), "MEMBER");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void listDefaultsToAllNotifications() throws Exception {
    NotificationDelivery unread =
        new NotificationDelivery(
            tenantId, "dedupe-1", NotificationChannel.WEB, "user@example.com", "welcome");
    when(service.list(tenantId, false)).thenReturn(List.of(unread));

    mockMvc
        .perform(
            get("/api/v1/notifications")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].channel").value("WEB"))
        .andExpect(jsonPath("$[0].read").value(false))
        .andExpect(jsonPath("$[0].readAt").doesNotExist());
  }

  @Test
  void unreadOnlyQueryParameterIsForwarded() throws Exception {
    when(service.list(tenantId, true)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/notifications")
                .param("unreadOnly", "true")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());

    verify(service).list(tenantId, true);
  }

  @Test
  void invalidUnreadOnlyValueReturns400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/notifications")
                .param("unreadOnly", "maybe")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://awap.dev/errors/AWAP-COMMON-0002"));
  }

  @Test
  void unreadCountReturnsTheTenantCount() throws Exception {
    when(service.unreadCount(tenantId)).thenReturn(7L);

    mockMvc
        .perform(
            get("/api/v1/notifications/unread-count")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(7));
  }

  @Test
  void markReadReturnsTheReadPayload() throws Exception {
    NotificationDelivery delivery = readDelivery();
    when(service.markRead(tenantId, delivery.getId())).thenReturn(delivery);

    mockMvc
        .perform(
            patch("/api/v1/notifications/{id}/read", delivery.getId())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(delivery.getId().toString()))
        .andExpect(jsonPath("$.read").value(true))
        .andExpect(jsonPath("$.readAt").exists());
  }

  @Test
  void markUnreadReturnsTheUnreadPayload() throws Exception {
    NotificationDelivery delivery =
        new NotificationDelivery(
            tenantId, "dedupe-2", NotificationChannel.WEB, "user@example.com", "welcome");
    when(service.markUnread(tenantId, delivery.getId())).thenReturn(delivery);

    mockMvc
        .perform(
            patch("/api/v1/notifications/{id}/unread", delivery.getId())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.read").value(false))
        .andExpect(jsonPath("$.readAt").doesNotExist());
  }

  @Test
  void unknownNotificationReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.markRead(tenantId, id))
        .thenThrow(
            new AwapException(
                ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Notification was not found."));

    mockMvc
        .perform(
            patch("/api/v1/notifications/{id}/read", id)
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void malformedIdentifierReturns400() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/notifications/{id}/read", "not-a-uuid")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void markReadWithoutAuthenticationReturns401() throws Exception {
    mockMvc
        .perform(patch("/api/v1/notifications/{id}/read", UUID.randomUUID()).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void markReadWithoutInstanceReadAuthorityReturns403() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/notifications/{id}/read", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void listWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
  }

  private NotificationDelivery readDelivery() {
    NotificationDelivery delivery =
        new NotificationDelivery(
            tenantId, "dedupe-1", NotificationChannel.WEB, "user@example.com", "welcome");
    assertThat(delivery.markRead(Instant.parse("2026-02-01T10:00:00Z"))).isTrue();
    return delivery;
  }
}
