package com.awap.organization.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
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
import com.awap.identity.domain.Role;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.organization.application.OrganizationService;
import com.awap.organization.interfaces.dto.CompanyResponse;
import com.awap.organization.interfaces.dto.DepartmentResponse;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.TeamResponse;
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
  void createCompanyReturns201() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.createCompany(any(), any()))
        .thenReturn(new CompanyResponse(UUID.randomUUID(), tenantId, "Acme"));

    mockMvc
        .perform(
            post("/api/v1/organizations/companies")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  void listCompaniesReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.listCompanies(any()))
        .thenReturn(List.of(new CompanyResponse(UUID.randomUUID(), tenantId, "Acme")));

    mockMvc
        .perform(
            get("/api/v1/organizations/companies")
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Acme"));
  }

  @Test
  void createDepartmentReturns201() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    UUID companyId = UUID.randomUUID();
    when(organizationService.createDepartment(any(), any()))
        .thenReturn(new DepartmentResponse(UUID.randomUUID(), tenantId, companyId, null, "IT"));

    mockMvc
        .perform(
            post("/api/v1/organizations/departments")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + companyId + "\",\"name\":\"IT\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("IT"));
  }

  @Test
  void listDepartmentsReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    UUID companyId = UUID.randomUUID();
    when(organizationService.listDepartments(any(), any()))
        .thenReturn(
            List.of(new DepartmentResponse(UUID.randomUUID(), tenantId, companyId, null, "IT")));

    mockMvc
        .perform(
            get("/api/v1/organizations/departments")
                .param("companyId", companyId.toString())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("IT"));
  }

  @Test
  void createTeamReturns201() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    UUID departmentId = UUID.randomUUID();
    when(organizationService.createTeam(any(), any()))
        .thenReturn(new TeamResponse(UUID.randomUUID(), tenantId, departmentId, "Core"));

    mockMvc
        .perform(
            post("/api/v1/organizations/teams")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"departmentId\":\"" + departmentId + "\",\"name\":\"Core\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Core"));
  }

  @Test
  void listTeamsReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    UUID departmentId = UUID.randomUUID();
    when(organizationService.listTeams(any(), any()))
        .thenReturn(List.of(new TeamResponse(UUID.randomUUID(), tenantId, departmentId, "Core")));

    mockMvc
        .perform(
            get("/api/v1/organizations/teams")
                .param("departmentId", departmentId.toString())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Core"));
  }

  @Test
  void createPositionReturns201() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.createPosition(any(), any(), any()))
        .thenReturn(new PositionResponse(UUID.randomUUID(), tenantId, "Finance", Role.MANAGER));

    mockMvc
        .perform(
            post("/api/v1/organizations/positions")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Finance\",\"role\":\"MANAGER\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("MANAGER"));
  }

  @Test
  void listPositionsReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.listPositions(any()))
        .thenReturn(
            List.of(new PositionResponse(UUID.randomUUID(), tenantId, "Finance", Role.EMPLOYEE)));

    mockMvc
        .perform(
            get("/api/v1/organizations/positions")
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Finance"));
  }

  @Test
  void createUserReturns201() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.createUser(any(), any()))
        .thenReturn(
            new OrgUserResponse(UUID.randomUUID(), tenantId, "a@acme.com", "A", null, null));

    mockMvc
        .perform(
            post("/api/v1/organizations/users")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@acme.com\",\"fullName\":\"A\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("a@acme.com"));
  }

  @Test
  void listUsersReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    when(organizationService.listUsers(any()))
        .thenReturn(
            List.of(
                new OrgUserResponse(UUID.randomUUID(), tenantId, "a@acme.com", "A", null, null)));

    mockMvc
        .perform(
            get("/api/v1/organizations/users")
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("a@acme.com"));
  }

  @Test
  void assignUserToTeamReturns200() throws Exception {
    UUID tenantId = TenantContext.requireTenantId();
    UUID teamId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(organizationService.assignToTeam(any(), any(), any()))
        .thenReturn(new OrgUserResponse(userId, tenantId, "a@acme.com", "A", teamId, null));

    mockMvc
        .perform(
            post("/api/v1/organizations/users/{id}/assign", userId)
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"" + teamId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamId").value(teamId.toString()));
  }

  @Test
  void aBlankCompanyNameReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/companies")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anOrganizationEndpointRequiresUserManage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/companies")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("TASK_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aMissingParentReturns404() throws Exception {
    when(organizationService.createDepartment(any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Company not found."));

    mockMvc
        .perform(
            post("/api/v1/organizations/departments")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + UUID.randomUUID() + "\",\"name\":\"IT\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aDuplicateCompanyReturns409() throws Exception {
    when(organizationService.createCompany(any(), any()))
        .thenThrow(
            new AwapException(
                ErrorCode.ORG_CONFLICT,
                HttpStatus.CONFLICT,
                "A company with this name already exists."));

    mockMvc
        .perform(
            post("/api/v1/organizations/companies")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void aMissingTenantContextReturns401() throws Exception {
    TenantContext.clear();

    mockMvc
        .perform(
            post("/api/v1/organizations/companies")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aMissingRoleContextReturns401() throws Exception {
    TenantContext.set(TenantContext.requireTenantId(), UUID.randomUUID(), null);

    mockMvc
        .perform(
            post("/api/v1/organizations/positions")
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Finance\",\"role\":\"MANAGER\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aMissingUserContextReturns401() throws Exception {
    TenantContext.set(TenantContext.requireTenantId(), null, "MANAGER");

    mockMvc
        .perform(
            put("/api/v1/organizations/positions/{id}/role", UUID.randomUUID())
                .with(csrf())
                .with(user("u").authorities(new SimpleGrantedAuthority("USER_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MANAGER\"}"))
        .andExpect(status().isUnauthorized());
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
