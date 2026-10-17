function apiBase(): string {
  return process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
}

export interface TaskDto {
  id: string;
  title: string;
  description: string | null;
  assigneeId: string | null;
  status: string;
  decisionComment: string | null;
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

export interface CompanyDto {
  id: string;
  name: string;
}

/** Reads the XSRF-TOKEN cookie written by Spring's CookieCsrfTokenRepository. */
function csrfToken(): string | undefined {
  if (typeof document === "undefined") {
    return undefined;
  }
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  const token = match?.[1];
  return token ? decodeURIComponent(token) : undefined;
}

/** Calls the public actuator health endpoint used by the shared consumer contract. */
export async function healthCheck(): Promise<HealthDto> {
  const response = await fetch(`${apiBase()}/actuator/health`);
  if (!response.ok) {
    throw new Error(`Health check failed with status ${response.status}`);
  }
  return (await response.json()) as HealthDto;
}

export interface RegisterTenantPayload {
  slug: string;
  name: string;
  adminEmail: string;
  adminPassword: string;
  adminFullName: string;
}

export async function registerTenant(payload: RegisterTenantPayload): Promise<void> {
  const response = await fetch(`${apiBase()}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error("Registration failed");
  }
}

export async function login(
  tenantSlug: string,
  email: string,
  password: string,
): Promise<void> {
  const response = await fetch(`${apiBase()}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ tenantSlug, email, password }),
  });
  if (!response.ok) {
    throw new Error("Login failed");
  }
}

async function refreshAccessToken(): Promise<boolean> {
  try {
    const response = await fetch(`${apiBase()}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: "{}",
    });
    return response.ok;
  } catch {
    return false;
  }
}

function redirectToLogin(): never {
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- full reload re-runs the middleware auth check
  window.location.href = "/";
  throw new Error("Unauthorized");
}

async function authFetch<T>(path: string, retried = false): Promise<T> {
  const headers: Record<string, string> = {};
  const token = csrfToken();
  if (token) {
    headers["X-XSRF-TOKEN"] = token;
  }
  const response = await fetch(`${apiBase()}${path}`, {
    headers,
    credentials: "include",
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

async function authPost<T>(path: string, body: unknown, retried = false): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const token = csrfToken();
  if (token) {
    headers["X-XSRF-TOKEN"] = token;
  }
  const response = await fetch(`${apiBase()}${path}`, {
    method: "POST",
    headers,
    credentials: "include",
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

export function listTasks(): Promise<TaskDto[]> {
  return authFetch<TaskDto[]>("/api/v1/tasks");
}

export function listWorkflowDefinitions(): Promise<WorkflowDefinitionDto[]> {
  return authFetch<WorkflowDefinitionDto[]>("/api/v1/workflow-definitions");
}

export function listWorkflowInstances(): Promise<WorkflowInstanceDto[]> {
  return authFetch<WorkflowInstanceDto[]>("/api/v1/workflow-instances");
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

export function approveTask(id: string, comment?: string): Promise<TaskDto> {
  return authPost<TaskDto>(`/api/v1/tasks/${id}/approve`, { comment });
}

export function rejectTask(id: string, comment?: string): Promise<TaskDto> {
  return authPost<TaskDto>(`/api/v1/tasks/${id}/reject`, { comment });
}

export function startWorkflowInstance(
  workflowDefinitionId: string,
  contextJson = "{}",
): Promise<WorkflowInstanceDto> {
  return authPost<WorkflowInstanceDto>("/api/v1/workflow-instances", {
    workflowDefinitionId,
    contextJson,
  });
}

export function listCompanies(): Promise<CompanyDto[]> {
  return authFetch<CompanyDto[]>("/api/v1/organizations/companies");
}

export interface OrgUserDto {
  id: string;
  email: string;
  fullName: string;
}

export interface TeamTreeDto {
  id: string;
  name: string;
  users: OrgUserDto[];
}

export interface DepartmentTreeDto {
  id: string;
  name: string;
  teams: TeamTreeDto[];
}

export interface CompanyTreeDto {
  id: string;
  name: string;
  departments: DepartmentTreeDto[];
}

export function fetchOrganizationTree(): Promise<CompanyTreeDto[]> {
  return authFetch<CompanyTreeDto[]>("/api/v1/organizations/tree");
}
