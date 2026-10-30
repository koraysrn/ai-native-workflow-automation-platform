package com.awap.notification.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.notification.application.NotificationTemplateService;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.domain.NotificationTemplateVersion;
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
class NotificationTemplateControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private NotificationTemplateService service;

  private MockMvc mockMvc;
  private UUID tenantId;
  private UUID actorId;
  private NotificationTemplate template;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    tenantId = UUID.randomUUID();
    actorId = UUID.randomUUID();
    TenantContext.set(tenantId, actorId, "MANAGER");
    template =
        new NotificationTemplate(tenantId, "welcome", "en", "Welcome", "Hello {{name}}", actorId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void listReturnsTemplatesForReaders() throws Exception {
    when(service.list(tenantId, null)).thenReturn(List.of(template));

    mockMvc
        .perform(
            get("/api/v1/notification-templates")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].templateKey").value("welcome"))
        .andExpect(jsonPath("$[0].locale").value("en"));
  }

  @Test
  void createReturns201WithTheNewTemplate() throws Exception {
    when(service.create(eq(tenantId), eq("welcome"), eq("tr"), any(), any(), eq(actorId)))
        .thenReturn(template);

    mockMvc
        .perform(
            post("/api/v1/notification-templates")
                .contentType("application/json")
                .content(
                    "{\"templateKey\":\"welcome\",\"locale\":\"tr\","
                        + "\"subject\":\"Merhaba\",\"body\":\"Merhaba {{name}}\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void createWithBlankBodyReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/notification-templates")
                .contentType("application/json")
                .content(
                    "{\"templateKey\":\"welcome\",\"locale\":\"tr\",\"subject\":\"Hi\",\"body\":\"\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void staleUpdateReturns409() throws Exception {
    when(service.update(eq(tenantId), any(), anyLong(), any(), any(), any()))
        .thenThrow(
            new AwapException(ErrorCode.RESOURCE_CONFLICT, HttpStatus.CONFLICT, "stale revision"));

    mockMvc
        .perform(
            put("/api/v1/notification-templates/{id}", template.getId())
                .contentType("application/json")
                .content("{\"expectedVersion\":9,\"subject\":\"Hi\",\"body\":\"Body\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void unknownTemplateReturns404() throws Exception {
    when(service.get(eq(tenantId), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Template was not found."));

    mockMvc
        .perform(
            get("/api/v1/notification-templates/{id}", UUID.randomUUID())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedIdReturns400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/notification-templates/{id}", "not-a-uuid")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void previewRendersWithoutSending() throws Exception {
    String escaped = "Hello " + "&" + "lt;b" + "&" + "gt;Ada" + "&" + "lt;/b" + "&" + "gt;";
    when(service.preview(eq(tenantId), eq("welcome"), eq("en"), any(), any(), any()))
        .thenReturn(new NotificationTemplateService.Preview("Welcome", escaped, List.of("name")));

    mockMvc
        .perform(
            post("/api/v1/notification-templates/preview")
                .contentType("application/json")
                .content(
                    "{\"templateKey\":\"welcome\",\"locale\":\"en\","
                        + "\"variablesJson\":\"{\\\"name\\\":\\\"Ada\\\"}\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.body").value(escaped))
        .andExpect(jsonPath("$.placeholders[0]").value("name"));
  }

  @Test
  void getReturnsTheTemplateRevision() throws Exception {
    when(service.get(tenantId, template.getId())).thenReturn(template);

    mockMvc
        .perform(
            get("/api/v1/notification-templates/{id}", template.getId())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(template.getId().toString()))
        .andExpect(jsonPath("$.body").value("Hello {{name}}"));
  }

  @Test
  void versionsReturnTheAuditTrail() throws Exception {
    when(service.versions(tenantId, template.getId()))
        .thenReturn(List.of(new NotificationTemplateVersion(template)));

    mockMvc
        .perform(
            get("/api/v1/notification-templates/{id}/versions", template.getId())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].subject").value("Welcome"))
        .andExpect(jsonPath("$[0].active").value(true));
  }

  @Test
  void updateReturnsTheNewRevision() throws Exception {
    when(service.update(eq(tenantId), eq(template.getId()), eq(0L), any(), any(), eq(actorId)))
        .thenReturn(template);

    mockMvc
        .perform(
            put("/api/v1/notification-templates/{id}", template.getId())
                .contentType("application/json")
                .content("{\"expectedVersion\":0,\"subject\":\"Welcome\",\"body\":\"Hi\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templateKey").value("welcome"));
  }

  @Test
  void archiveReturnsTheArchivedTemplate() throws Exception {
    when(service.archive(tenantId, template.getId(), actorId)).thenReturn(template);

    mockMvc
        .perform(
            post("/api/v1/notification-templates/{id}/archive", template.getId())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void activateReturnsTheRestoredTemplate() throws Exception {
    when(service.activate(tenantId, template.getId(), actorId)).thenReturn(template);

    mockMvc
        .perform(
            post("/api/v1/notification-templates/{id}/activate", template.getId())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void missingTenantContextReturns401() throws Exception {
    TenantContext.clear();

    mockMvc
        .perform(
            get("/api/v1/notification-templates")
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createWithoutUserContextReturns401() throws Exception {
    TenantContext.set(tenantId, null, "MANAGER");

    mockMvc
        .perform(
            post("/api/v1/notification-templates")
                .contentType("application/json")
                .content(
                    "{\"templateKey\":\"welcome\",\"locale\":\"tr\","
                        + "\"subject\":\"Merhaba\",\"body\":\"Merhaba {{name}}\"}")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_MANAGE"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void archiveRequiresManageAuthority() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/notification-templates/{id}/archive", template.getId())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedRequestReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/notification-templates")).andExpect(status().isUnauthorized());
  }
}
