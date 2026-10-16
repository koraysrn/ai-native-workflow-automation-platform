package com.awap.organization.interfaces.api;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.organization.application.OrganizationService;
import com.awap.organization.interfaces.dto.CompanyRequest;
import com.awap.organization.interfaces.dto.CompanyResponse;
import com.awap.organization.interfaces.dto.DepartmentRequest;
import com.awap.organization.interfaces.dto.DepartmentResponse;
import com.awap.organization.interfaces.dto.OrgUserRequest;
import com.awap.organization.interfaces.dto.OrgUserResponse;
import com.awap.organization.interfaces.dto.PositionRequest;
import com.awap.organization.interfaces.dto.PositionResponse;
import com.awap.organization.interfaces.dto.TeamAssignRequest;
import com.awap.organization.interfaces.dto.TeamRequest;
import com.awap.organization.interfaces.dto.TeamResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Organization hierarchy endpoints. */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

  private final OrganizationService organizationService;

  public OrganizationController(OrganizationService organizationService) {
    this.organizationService = organizationService;
  }

  @PostMapping("/companies")
  public ResponseEntity<CompanyResponse> createCompany(@Valid @RequestBody CompanyRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(organizationService.createCompany(currentTenantId(), request));
  }

  @GetMapping("/companies")
  public ResponseEntity<List<CompanyResponse>> listCompanies() {
    return ResponseEntity.ok(organizationService.listCompanies(currentTenantId()));
  }

  @PostMapping("/departments")
  public ResponseEntity<DepartmentResponse> createDepartment(
      @Valid @RequestBody DepartmentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(organizationService.createDepartment(currentTenantId(), request));
  }

  @GetMapping("/departments")
  public ResponseEntity<List<DepartmentResponse>> listDepartments(@RequestParam UUID companyId) {
    return ResponseEntity.ok(organizationService.listDepartments(currentTenantId(), companyId));
  }

  @PostMapping("/teams")
  public ResponseEntity<TeamResponse> createTeam(@Valid @RequestBody TeamRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(organizationService.createTeam(currentTenantId(), request));
  }

  @GetMapping("/teams")
  public ResponseEntity<List<TeamResponse>> listTeams(@RequestParam UUID departmentId) {
    return ResponseEntity.ok(organizationService.listTeams(currentTenantId(), departmentId));
  }

  @PostMapping("/positions")
  public ResponseEntity<PositionResponse> createPosition(
      @Valid @RequestBody PositionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(organizationService.createPosition(currentTenantId(), request));
  }

  @GetMapping("/positions")
  public ResponseEntity<List<PositionResponse>> listPositions() {
    return ResponseEntity.ok(organizationService.listPositions(currentTenantId()));
  }

  @PostMapping("/users")
  public ResponseEntity<OrgUserResponse> createUser(@Valid @RequestBody OrgUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(organizationService.createUser(currentTenantId(), request));
  }

  @GetMapping("/users")
  public ResponseEntity<List<OrgUserResponse>> listUsers() {
    return ResponseEntity.ok(organizationService.listUsers(currentTenantId()));
  }

  @PostMapping("/users/{id}/assign")
  public ResponseEntity<OrgUserResponse> assignToTeam(
      @PathVariable UUID id, @Valid @RequestBody TeamAssignRequest request) {
    return ResponseEntity.ok(
        organizationService.assignToTeam(currentTenantId(), id, request.teamId()));
  }

  private UUID currentTenantId() {
    return TenantContext.current()
        .map(TenantContext.Context::tenantId)
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."));
  }
}
