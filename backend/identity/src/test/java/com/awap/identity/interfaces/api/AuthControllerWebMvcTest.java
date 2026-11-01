package com.awap.identity.interfaces.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.application.AuthService;
import com.awap.identity.application.TokenPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class AuthControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private AuthService authService;

  @Test
  void loginReturns200AndSetsCookies() throws Exception {
    when(authService.login(anyString(), anyString(), anyString()))
        .thenReturn(new TokenPair("access", "refresh", 900L));

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantSlug\":\"acme\",\"email\":\"a@b.c\",\"password\":\"pw\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void csrfEndpointIssuesTheXsrfCookieAndReturnsTheRawToken() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    mockMvc
        .perform(get("/api/v1/auth/csrf").with(user("user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(cookie().exists("XSRF-TOKEN"));
  }
}
