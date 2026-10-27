function apiBase(): string {
  return process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
}

export interface TaskDto {
  id: string;
  tenantId: string;
  workflowInstanceId: string | null;
  title: string;
  description: string | null;
  assigneeId: string | null;
  status: string;
  version: number;
  decisionComment: string | null;
}

export interface WorkflowDefinitionDto {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  status: string;
  currentVersion: number;
}

export interface WorkflowInstanceDto {
  id: string;
  tenantId: string;
  workflowDefinitionId: string;
  workflowVersion: number;
  state: string;
  contextJson: string;
  idempotencyKey: string | null;
  createdAt: string;
}

export interface FormDefinitionDto {
  id: string;
  tenantId: string;
  name: string;
  schemaJson: string;
  version: number;
  createdAt: string;
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

/** Reads the active tenant cookie set by the tenant switcher. */
function activeTenantId(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const match = document.cookie.match(/(?:^|;\s*)awap_tenant=([^;]*)/);
  const value = match?.[1];
  return value ? decodeURIComponent(value) : null;
}

/** Logs an API call to the browser console for local diagnostics. */
function logApi(method: string, path: string, status: number, started: number): void {
  console.log(
    `[api] ${method} ${path} -> ${status} (${Math.round(performance.now() - started)}ms)`,
  );
}

/** Calls the public actuator health endpoint used by the shared consumer contract. */
export async function healthCheck(): Promise<HealthDto> {
  const started = performance.now();
  const response = await fetch(`${apiBase()}/actuator/health`);
  logApi("GET", "/actuator/health", response.status, started);
  if (!response.ok) {
    throw new Error(`Health check failed with status ${response.status}`);
  }
  return (await response.json()) as HealthDto;
}

export interface TenantMembershipDto {
  tenantId: string;
  role: string;
}

export async function fetchTenants(): Promise<TenantMembershipDto[]> {
  const response = await fetch(`${apiBase()}/api/v1/auth/tenants`, {
    credentials: "include",
  });
  if (!response.ok) {
    throw new Error(`Failed to load tenants with status ${response.status}`);
  }
  return (await response.json()) as TenantMembershipDto[];
}

export interface TenantDto {
  id: string;
  slug: string;
  name: string;
  plan: string;
  maxUsers: number;
  maxWorkflows: number;
  maxAiCallsPerMonth: number;
  active: boolean;
}

export interface RegisterTenantPayload {
  slug: string;
  name: string;
  adminEmail: string;
  adminPassword: string;
  adminFullName: string;
}

export async function registerTenant(payload: RegisterTenantPayload): Promise<TenantDto> {
  const started = performance.now();
  const response = await fetch(`${apiBase()}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(payload),
  });
  logApi("POST", "/api/v1/tenants", response.status, started);
  if (!response.ok) {
    throw new Error(await readErrorDetail(response, "Registration failed"));
  }
  return (await response.json()) as TenantDto;
}

/** Extracts the RFC 7807 `detail` (or `title`) from a failed response, if present. */
async function readErrorDetail(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown; title?: unknown };
    if (typeof body.detail === "string") {
      return body.detail;
    }
    if (typeof body.title === "string") {
      return body.title;
    }
  } catch {
    // Response was not JSON; fall through to the generic message.
  }
  return `${fallback} (HTTP ${response.status})`;
}

export interface TokenDto {
  accessToken: string | null;
  refreshToken: string | null;
  expiresIn: number;
}

export async function login(
  tenantSlug: string,
  email: string,
  password: string,
): Promise<TokenDto> {
  const response = await fetch(`${apiBase()}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ tenantSlug, email, password }),
  });
  if (!response.ok) {
    throw new Error("Login failed");
  }
  return (await response.json()) as TokenDto;
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
  const tenant = activeTenantId();
  if (tenant) {
    headers["X-Tenant-Id"] = tenant;
  }
  const started = performance.now();
  const response = await fetch(`${apiBase()}${path}`, {
    headers,
    credentials: "include",
  });
  logApi("GET", path, response.status, started);

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

async function authPatch<T>(path: string, retried = false): Promise<T> {
  const headers: Record<string, string> = {};
  const token = csrfToken();
  if (token) {
    headers["X-XSRF-TOKEN"] = token;
  }
  const tenant = activeTenantId();
  if (tenant) {
    headers["X-Tenant-Id"] = tenant;
  }
  const started = performance.now();
  const response = await fetch(`${apiBase()}${path}`, {
    method: "PATCH",
    headers,
    credentials: "include",
  });
  logApi("PATCH", path, response.status, started);

  if (response.status === 401 && !retried && (await refreshAccessToken())) {
    return authPatch<T>(path, true);
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
  const tenant = activeTenantId();
  if (tenant) {
    headers["X-Tenant-Id"] = tenant;
  }
  const started = performance.now();
  const response = await fetch(`${apiBase()}${path}`, {
    method: "POST",
    headers,
    credentials: "include",
    body: JSON.stringify(body),
  });
  logApi("POST", path, response.status, started);

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

export interface FormDefinitionDetailDto extends FormDefinitionDto {
  schemaJson: string;
}

export function fetchFormDefinitions(): Promise<FormDefinitionDto[]> {
  return authFetch<FormDefinitionDto[]>("/api/v1/forms");
}

export function fetchFormDefinition(id: string): Promise<FormDefinitionDetailDto> {
  return authFetch<FormDefinitionDetailDto>(`/api/v1/forms/${id}`);
}

export interface SubmitFormPayload {
  dataJson: string;
  workflowInstanceId?: string | null;
  nodeExecutionId?: string | null;
}

export function submitForm(formId: string, payload: SubmitFormPayload): Promise<unknown> {
  return authPost<unknown>(`/api/v1/forms/${formId}/submissions`, payload);
}

export interface AnalyticsAggregateDto {
  day: string;
  eventType: string;
  count: number;
  totalDurationMs: number;
  averageDurationMs: number;
}

export function fetchAnalyticsSummary(
  from?: string,
  to?: string,
): Promise<AnalyticsAggregateDto[]> {
  const params = new URLSearchParams();
  if (from) {
    params.set("from", from);
  }
  if (to) {
    params.set("to", to);
  }
  const query = params.toString();
  return authFetch<AnalyticsAggregateDto[]>(
    `/api/v1/analytics/summary${query ? `?${query}` : ""}`,
  );
}

export interface DocumentVersionDto {
  id: string;
  fileId: string;
  version: number;
  size: number;
  format: string | null;
  status: string;
  createdAt: string;
}

export interface CreateDocumentResponseDto {
  fileId: string;
  uploadUrl: string;
  version: DocumentVersionDto;
}

export function listDocuments(): Promise<DocumentVersionDto[]> {
  return authFetch<DocumentVersionDto[]>("/api/v1/documents");
}

export function createDocumentUpload(
  name: string,
  contentType: string,
  size: number,
): Promise<CreateDocumentResponseDto> {
  return authPost<CreateDocumentResponseDto>("/api/v1/documents", {
    name,
    contentType,
    size,
  });
}

export function getDocumentDownloadUrl(fileId: string): Promise<{ downloadUrl: string }> {
  return authFetch<{ downloadUrl: string }>(`/api/v1/documents/${fileId}/download`);
}

export function archiveDocumentVersion(versionId: string): Promise<DocumentVersionDto> {
  return authPost<DocumentVersionDto>(`/api/v1/documents/versions/${versionId}/archive`, {});
}

export interface NotificationDeliveryDto {
  id: string;
  channel: string;
  recipient: string;
  templateKey: string | null;
  status: string;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  read: boolean;
  readAt: string | null;
}

export interface UnreadNotificationCountDto {
  count: number;
}

export function listNotifications(unreadOnly = false): Promise<NotificationDeliveryDto[]> {
  const query = unreadOnly ? "?unreadOnly=true" : "";
  return authFetch<NotificationDeliveryDto[]>(`/api/v1/notifications${query}`);
}

export function fetchUnreadNotificationCount(): Promise<UnreadNotificationCountDto> {
  return authFetch<UnreadNotificationCountDto>("/api/v1/notifications/unread-count");
}

export function markNotificationRead(id: string): Promise<NotificationDeliveryDto> {
  return authPatch<NotificationDeliveryDto>(`/api/v1/notifications/${encodeURIComponent(id)}/read`);
}

export function markNotificationUnread(id: string): Promise<NotificationDeliveryDto> {
  return authPatch<NotificationDeliveryDto>(
    `/api/v1/notifications/${encodeURIComponent(id)}/unread`,
  );
}
