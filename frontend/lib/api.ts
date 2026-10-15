const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

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

export interface CreateWorkflowPayload {
  name: string;
  description?: string;
  definitionJson: string;
}

export async function login(
  tenantSlug: string,
  email: string,
  password: string,
): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tenantSlug, email, password }),
  });

  if (!response.ok) {
    throw new Error("Login failed");
  }

  return (await response.json()) as LoginResponse;
}

async function authFetch<T>(path: string): Promise<T> {
  const accessToken = localStorage.getItem("awap_access_token");
  const response = await fetch(`${API_BASE}${path}`, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
  });

  if (response.status === 401) {
    window.location.href = "/";
    throw new Error("Unauthorized");
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

async function authPost<T>(path: string, body: unknown): Promise<T> {
  const accessToken = localStorage.getItem("awap_access_token");
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify(body),
  });

  if (response.status === 401) {
    window.location.href = "/";
    throw new Error("Unauthorized");
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
