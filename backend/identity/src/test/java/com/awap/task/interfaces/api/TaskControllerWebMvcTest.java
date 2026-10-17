package com.awap.task.interfaces.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awap.identity.IdentityApplication;
import com.awap.identity.infrastructure.security.TenantContext;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = IdentityApplication.class)
class TaskControllerWebMvcTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private TaskService taskService;

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
  void listRequiresTaskManageAuthority() throws Exception {
    when(taskService.list(any(UUID.class))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/tasks")
                .with(user("user").authorities(new SimpleGrantedAuthority("TASK_MANAGE"))))
        .andExpect(status().isOk());
  }

  @Test
  void listForbiddenWithoutAuthority() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/tasks")
                .with(user("user").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void listUnauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/tasks")).andExpect(status().isUnauthorized());
  }

  @Test
  void approveWithCommentPassesCommentToService() throws Exception {
    UUID id = UUID.randomUUID();
    when(taskService.approve(
            any(UUID.class), any(UUID.class), any(UUID.class), any(String.class), any(String.class)))
        .thenReturn(taskResponse());

    mockMvc
        .perform(
            post("/api/v1/tasks/{id}/approve", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Looks good\"}")
                .with(user("user").authorities(new SimpleGrantedAuthority("TASK_APPROVE"))))
        .andExpect(status().isOk());

    verify(taskService)
        .approve(any(UUID.class), eq(id), any(UUID.class), any(String.class), eq("Looks good"));
  }

  @Test
  void approveWithoutBodySucceedsWithNullComment() throws Exception {
    UUID id = UUID.randomUUID();
    when(taskService.approve(
            any(UUID.class), any(UUID.class), any(UUID.class), any(String.class), any(String.class)))
        .thenReturn(taskResponse());

    mockMvc
        .perform(
            post("/api/v1/tasks/{id}/approve", id)
                .with(user("user").authorities(new SimpleGrantedAuthority("TASK_APPROVE"))))
        .andExpect(status().isOk());

    verify(taskService)
        .approve(any(UUID.class), eq(id), any(UUID.class), any(String.class), isNull());
  }

  @Test
  void rejectWithCommentPassesCommentToService() throws Exception {
    UUID id = UUID.randomUUID();
    when(taskService.reject(
            any(UUID.class), any(UUID.class), any(UUID.class), any(String.class), any(String.class)))
        .thenReturn(taskResponse());

    mockMvc
        .perform(
            post("/api/v1/tasks/{id}/reject", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Needs rework\"}")
                .with(user("user").authorities(new SimpleGrantedAuthority("TASK_APPROVE"))))
        .andExpect(status().isOk());

    verify(taskService)
        .reject(any(UUID.class), eq(id), any(UUID.class), any(String.class), eq("Needs rework"));
  }

  private TaskResponse taskResponse() {
    return new TaskResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        "Review",
        null,
        UUID.randomUUID(),
        "APPROVED",
        0L,
        null);
  }
}
