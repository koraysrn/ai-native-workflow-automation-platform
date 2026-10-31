package com.awap.document.interfaces.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.document.application.DocumentScanSseService;
import com.awap.document.application.DocumentService;
import com.awap.document.domain.DocumentStatus;
import com.awap.document.domain.DocumentVersion;
import com.awap.file.application.FileService;
import com.awap.file.application.FileService.FileScanInfo;
import com.awap.file.domain.FileScanStatus;
import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(classes = IdentityApplication.class)
class DocumentControllerWebMvcTest {

  private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private WebApplicationContext context;
  @MockitoBean private DocumentService documentService;
  @MockitoBean private FileService fileService;
  @MockitoBean private DocumentScanSseService scanSseService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    TenantContext.set(TENANT, USER, "MANAGER");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void listProjectsTheTenantScopedScanStatusIntoEachVersion() throws Exception {
    UUID fileId = UUID.randomUUID();
    DocumentVersion version =
        new DocumentVersion(TENANT, fileId, 1, "awap-quarantine", "k", 12, "pdf");
    when(documentService.list(TENANT)).thenReturn(List.of(version));
    when(fileService.scanInfo(eq(TENANT), anyList()))
        .thenReturn(
            List.of(new FileScanInfo(fileId, "quarterly.pdf", FileScanStatus.PROMOTED, null)));

    mockMvc
        .perform(
            get("/api/v1/documents")
                .with(user("manager").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].fileId").value(fileId.toString()))
        .andExpect(jsonPath("$[0].name").value("quarterly.pdf"))
        .andExpect(jsonPath("$[0].scanStatus").value("PROMOTED"))
        .andExpect(jsonPath("$[0].downloadable").value(true))
        .andExpect(jsonPath("$[0].status").value(DocumentStatus.PENDING.name()));
  }

  @Test
  void eventsEndpointRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/documents/events")).andExpect(status().isUnauthorized());
  }

  @Test
  void eventsEndpointRequiresReadAuthority() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/documents/events")
                .with(user("employee").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void tenantWideEventsStreamStartsWithTheTenantSnapshot() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileService.tenantScanInfo(TENANT))
        .thenReturn(List.of(new FileScanInfo(fileId, "a.pdf", FileScanStatus.SCANNING, null)));
    when(scanSseService.open(eq(TENANT), eq(USER), eq(null), anyList()))
        .thenReturn(new SseEmitter(1000L));

    mockMvc
        .perform(
            get("/api/v1/documents/events")
                .with(user("manager").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(request().asyncStarted())
        .andExpect(status().isOk());
  }

  @Test
  void perFileEventsRejectACrossTenantFileWith404() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileService.scanInfo(TENANT, fileId))
        .thenThrow(
            new AwapException(ErrorCode.FILE_NOT_FOUND, HttpStatus.NOT_FOUND, "File not found."));

    mockMvc
        .perform(
            get("/api/v1/documents/" + fileId + "/events")
                .with(user("manager").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void perFileEventsOpensOnlyAfterTheOwnershipCheck() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileService.scanInfo(TENANT, fileId))
        .thenReturn(new FileScanInfo(fileId, "a.pdf", FileScanStatus.CLEAN, null));
    when(scanSseService.open(eq(TENANT), eq(USER), eq(fileId), anyList()))
        .thenReturn(new SseEmitter(1000L));

    mockMvc
        .perform(
            get("/api/v1/documents/" + fileId + "/events")
                .with(user("manager").authorities(new SimpleGrantedAuthority("INSTANCE_READ"))))
        .andExpect(request().asyncStarted())
        .andExpect(status().isOk());
  }
}
