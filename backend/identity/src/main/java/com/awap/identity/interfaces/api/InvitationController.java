package com.awap.identity.interfaces.api;

import com.awap.identity.application.TenantService;
import com.awap.identity.interfaces.dto.AcceptInvitationRequest;
import com.awap.identity.interfaces.dto.TenantResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint for accepting a tenant invitation token. */
@RestController
@RequestMapping("/api/v1/tenant-invitations")
public class InvitationController {

  private final TenantService tenantService;

  public InvitationController(TenantService tenantService) {
    this.tenantService = tenantService;
  }

  @PostMapping("/{token}/accept")
  public ResponseEntity<TenantResponse> accept(
      @PathVariable String token, @RequestBody(required = false) AcceptInvitationRequest request) {
    AcceptInvitationRequest safe =
        request != null ? request : new AcceptInvitationRequest(null, null);
    return ResponseEntity.ok(tenantService.acceptInvitation(token, safe));
  }
}
