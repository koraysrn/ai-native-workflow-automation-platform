function apiBase(): string {
  return process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface TaskDto {
  id: string;
  title: string;
  description: string | null;
  assigneeId: string | null;
  status: string;
}

export interface WorkflowDefinitionDto {
  id: string;
  name: string;
  description: string | null;
  status: string;
  currentVersion: number;
}

export interface WorkflowInstanceDto {
  id: string;
  workflowDefinitionId: string;
  state: string;
  createdAt: string;
}

export interface FormDefinitionDto {
  id: string;
  name: string;
  version: number;
}

export interface HealthDto {
  status: string;
}

export interface CreateWorkflowPayload {
  name: string;
  description?: string;
  definitionJson: string;
}

/** Calls the public actuator health endpoint used by the shared consumer contract. */
export async function healthCheck(): Promise<HealthDto> {
  const response = await fetch(`${apiBase()}/actuator/health`);
  if (!response.ok) {
    throw new Error(`Health check failed with status ${response.status}`);
  }
  return (await response.json()) as HealthDto;
}

export async function login(
  tenantSlug: string,
  email: string,
  password: string,
): Promise<LoginResponse> {
  const response = await fetch(`${apiBase()}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tenantSlug, email, password }),
  });

  if (!response.ok) {
    throw new Error("Login failed");
  }

  return (await response.json()) as LoginResponse;
}

async function refreshAccessToken(): Promise<boolean> {
  const refreshToken = localStorage.getItem("awap_refresh_token");
  if (!refreshToken) {
    return false;
  }
  try {
    const response = await fetch(`${apiBase()}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!response.ok) {
      return false;
    }
    const tokens = (await response.json()) as {
      accessToken: string;
      refreshToken: string;
    };
    localStorage.setItem("awap_access_token", tokens.accessToken);
    localStorage.setItem("awap_refresh_token", tokens.refreshToken);
    return true;
  } catch {
    return false;
  }
}

function redirectToLogin(): never {
  localStorage.removeItem("awap_access_token");
  localStorage.removeItem("awap_refresh_token");
  window.location.href = "/";
  throw new Error("Unauthorized");
}

async function authFetch<T>(path: string, retried = false): Promise<T> {
  const accessToken = localStorage.getItem("awap_access_token");
  const response = await fetch(`${apiBase()}${path}`, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
  });

  if (response.status === 401 && !retried && (await refreshAccessToken())) {
    return authFetch<T>(path, true);
  }
  if (response.status === 401) {
    redirectToLogin();
  }

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }

  return (await response.json()) as T;
}

export function listTasks(): Promise<TaskDto[]> {
  return authFetch<TaskDto[]>("/api/v1/tasks");
}

export function listWorkflowDefinitions(): Promise<WorkflowDefinitionDto[]> {
  return authFetch<WorkflowDefinitionDto[]>("/api/v1/workflow-definitions");
}

export function listWorkflowInstances(): Promise<WorkflowInstanceDto[]> {
  return authFetch<WorkflowInstanceDto[]>("/api/v1/workflow-instances");
}

async function authPost<T>(path: string, body: unknown, retried = false): Promise<T> {
  const accessToken = localStorage.getItem("awap_access_token");
  const response = await fetch(`${apiBase()}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify(body),
  });

  if (response.status === 401 && !retried && (await refreshAccessToken())) {
    return authPost<T>(path, body, true);
  }
  if (response.status === 401) {
    redirectToLogin();
  }

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }

  return (await response.json()) as T;
}

export function createWorkflowDefinition(
  payload: CreateWorkflowPayload,
): Promise<WorkflowDefinitionDto> {
  return authPost<WorkflowDefinitionDto>("/api/v1/workflow-definitions", payload);
}

export function createFormDefinition(
  name: string,
  schemaJson: string,
): Promise<FormDefinitionDto> {
  return authPost<FormDefinitionDto>("/api/v1/forms", { name, schemaJson });
}

export function approveTask(id: string): Promise<TaskDto> {
  return authPost<TaskDto>(`/api/v1/tasks/${id}/approve`, {});
}

export function rejectTask(id: string): Promise<TaskDto> {
  return authPost<TaskDto>(`/api/v1/tasks/${id}/reject`, {});
}

export function startWorkflowInstance(
  workflowDefinitionId: string,
): Promise<WorkflowInstanceDto> {
  return authPost<WorkflowInstanceDto>("/api/v1/workflow-instances", {
    workflowDefinitionId,
  });
}

export interface CompanyDto {
  id: string;
  name: string;
}

export function listCompanies(): Promise<CompanyDto[]> {
  return authFetch<CompanyDto[]>("/api/v1/organizations/companies");
}
