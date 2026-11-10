package com.awap.organization.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.IdentityApplication;
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.organization.application.OrganizationService;
import com.awap.organization.interfaces.dto.PositionResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class OrganizationControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private OrganizationService organizationService;

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
  void treeRequiresUserManage() throws Exception {
    when(organizationService.getTree(any(UUID.class))).thenReturn(List.of());
    mockMvc
        .perform(
            get("/api/v1/organizations/tree")
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk());
  }

  @Test
  void treeUnauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/organizations/tree")).andExpect(status().isUnauthorized());
  }

  @Test
  void updatePositionRoleReturnsTheNewRole() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.updatePositionRole(any(), any(), any(), any(), any()))
        .thenReturn(new PositionResponse(id, UUID.randomUUID(), "Finance", Role.MANAGER));

    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", id)
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MANAGER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("MANAGER"));
  }

  @Test
  void updatePositionRoleRequiresUserManage() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("TASK_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MANAGER\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnknownRoleReturns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SUPER_USER\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aMissingRoleReturns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aForeignPositionReturns404() throws Exception {
    when(organizationService.updatePositionRole(any(), any(), any(), any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Position not found."));

    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MANAGER\"}"))
        .andExpect(status().isNotFound());
  }
}
