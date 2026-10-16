package com.awap.identity.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.application.TenantService;
import com.awap.identity.interfaces.dto.TenantResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class TenantControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private TenantService tenantService;

  @Test
  void createTenantReturns201() throws Exception {
    UUID tenantId = UUID.randomUUID();
    when(tenantService.createTenant(any()))
        .thenReturn(new TenantResponse(tenantId, "acme", "Acme", "FREE", 100, 50, 1000, true));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"acme\",\"name\":\"Acme\",\"adminEmail\":\"a@b.c\","
                        + "\"adminPassword\":\"password123\",\"adminFullName\":\"Admin\"}"))
        .andExpect(status().isCreated());
  }
}
