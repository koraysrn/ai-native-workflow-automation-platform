import type { Page } from "@playwright/test";

/**
 * Shared accessibility fixtures (issue #184).
 *
 * The suite runs against deterministic API mocks so every screen can be
 * exercised in both empty and populated states without a backend. The same
 * fixture seeds the session cookies the middleware and RouteGuard require.
 */

const API = "http://localhost:8080";

const now = "2026-11-09T10:00:00.000Z";

export const taskFixture = {
  id: "task-1",
  tenantId: "tenant-a",
  workflowInstanceId: "inst-1",
  title: "Approve expense",
  description: "Approve the Q4 expense report.",
  assigneeId: "user-1",
  status: "OPEN",
  version: 1,
  decisionComment: null,
};

export const instanceFixture = {
  id: "inst-1",
  tenantId: "tenant-a",
  workflowDefinitionId: "wf-1",
  workflowVersion: 1,
  state: "RUNNING",
  contextJson: "{}",
  idempotencyKey: null,
  createdAt: now,
};

export const workflowFixture = {
  id: "wf-1",
  tenantId: "tenant-a",
  name: "Expense approval",
  description: "Two-step expense approval",
  status: "PUBLISHED",
  currentVersion: 1,
};

export const formFixture = {
  id: "form-1",
  tenantId: "tenant-a",
  name: "Customer intake",
  schemaJson: JSON.stringify({
    fields: [
      { id: "customer_name", type: "TEXT", required: true },
      { id: "tier", type: "SELECT", options: ["Gold", "Silver"] },
      { id: "notes", type: "TEXTAREA" },
    ],
  }),
  version: 1,
  createdAt: now,
};

export const analyticsFixture = [
  {
    day: "2026-11-08",
    eventType: "WORKFLOW_COMPLETED",
    count: 12,
    totalDurationMs: 24000,
    averageDurationMs: 2000,
  },
  {
    day: "2026-11-08",
    eventType: "WORKFLOW_FAILED",
    count: 2,
    totalDurationMs: 4000,
    averageDurationMs: 2000,
  },
  {
    day: "2026-11-08",
    eventType: "TASK_COMPLETED",
    count: 30,
    totalDurationMs: 30000,
    averageDurationMs: 1000,
  },
];

export const documentFixture = {
  id: "version-1",
  fileId: "file-1",
  version: 1,
  size: 2048,
  format: "pdf",
  status: "PROMOTED",
  createdAt: now,
  name: "contract.pdf",
  scanStatus: "PROMOTED",
  scanError: null,
  downloadable: true,
};

export const notificationFixture = {
  id: "notification-1",
  channel: "EMAIL",
  recipient: "ada@acme.com",
  templateKey: "task.assigned",
  status: "DELIVERED",
  attempts: 1,
  lastError: null,
  createdAt: now,
  read: false,
  readAt: null,
};

export const notificationTemplateFixture = {
  id: "template-1",
  templateKey: "task.assigned",
  locale: "en",
  subject: "Task assigned",
  body: "Hi {{name}}, a task needs your attention.",
  version: 1,
  active: true,
  createdAt: now,
  updatedAt: now,
};

export const organizationTreeFixture = [
  {
    id: "company-1",
    name: "Acme Inc",
    departments: [
      {
        id: "department-1",
        name: "Engineering",
        teams: [
          {
            id: "team-1",
            name: "Platform",
            users: [{ id: "user-1", email: "ada@acme.com", fullName: "Ada Lovelace" }],
          },
        ],
      },
    ],
  },
];

const memberships = [
  { tenantId: "tenant-a", role: "TENANT_ADMIN" },
  { tenantId: "tenant-b", role: "EMPLOYEE" },
];

/** Chooses the response body for a mocked API request. */
export function responseFor(pathname: string, populated: boolean): unknown {
  if (pathname.endsWith("/actuator/health")) {
    return { status: "UP" };
  }
  if (pathname.endsWith("/api/v1/auth/tenants")) {
    return memberships;
  }
  if (pathname.endsWith("/api/v1/auth/csrf")) {
    return {};
  }
  if (pathname.endsWith("/api/v1/tasks")) {
    return populated ? [taskFixture] : [];
  }
  if (pathname.endsWith("/api/v1/workflow-instances")) {
    return populated ? [instanceFixture] : [];
  }
  if (pathname.endsWith("/api/v1/workflow-definitions")) {
    return populated ? [workflowFixture] : [];
  }
  if (pathname.endsWith("/api/v1/forms/form-1")) {
    return formFixture;
  }
  if (pathname.endsWith("/api/v1/forms")) {
    return populated ? [formFixture] : [];
  }
  if (pathname.endsWith("/api/v1/analytics/summary")) {
    return populated ? analyticsFixture : [];
  }
  if (pathname.endsWith("/api/v1/documents")) {
    return populated ? [documentFixture] : [];
  }
  if (pathname.endsWith("/api/v1/organizations/companies")) {
    return populated ? [{ id: "company-1", name: "Acme Inc" }] : [];
  }
  if (pathname.endsWith("/api/v1/organizations/tree")) {
    return populated ? organizationTreeFixture : [];
  }
  if (pathname.endsWith("/api/v1/notifications/unread-count")) {
    return { count: populated ? 1 : 0 };
  }
  if (pathname.endsWith("/api/v1/notifications")) {
    return populated ? [notificationFixture] : [];
  }
  if (pathname.endsWith("/api/v1/notification-templates")) {
    return populated ? [notificationTemplateFixture] : [];
  }
  return [];
}

/** Installs deterministic API mocks for the current page. */
export async function installApiMocks(page: Page, populated: boolean): Promise<void> {
  await page.route(`${API}/**`, async (route) => {
    const request = route.request();
    if (request.method() !== "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({}),
      });
      return;
    }
    const pathname = new URL(request.url()).pathname;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(responseFor(pathname, populated)),
    });
  });
}

/** Seeds the cookies the middleware and RouteGuard rely on. */
export async function setSession(
  page: Page,
  options: { tenant?: string | null } = {},
): Promise<void> {
  const cookies = [
    { name: "awap_session", value: "1", url: "http://localhost:3000", sameSite: "Lax" as const },
  ];
  if (options.tenant !== null && options.tenant !== undefined) {
    cookies.push({
      name: "awap_tenant",
      value: options.tenant,
      url: "http://localhost:3000",
      sameSite: "Lax" as const,
    });
  }
  await page.context().addCookies(cookies);
}

export interface ScreenDefinition {
  name: string;
  path: string;
  heading: string;
  /** Protected application screen; seeds `awap_session` and `awap_tenant`. */
  protected?: boolean;
  /** Seeds only `awap_session` (the tenant picker). */
  sessionOnly?: boolean;
  states: Array<"empty" | "populated">;
}

export const SCREENS: ScreenDefinition[] = [
  { name: "login", path: "/", heading: "AWAP", states: ["empty"] },
  { name: "register", path: "/register", heading: "Create tenant", states: ["empty"] },
  {
    name: "select-tenant",
    path: "/select-tenant",
    heading: "Select tenant",
    sessionOnly: true,
    states: ["populated"],
  },
  { name: "dashboard", path: "/dashboard", heading: "Dashboard", protected: true, states: ["empty", "populated"] },
  { name: "tasks", path: "/tasks", heading: "Tasks", protected: true, states: ["empty", "populated"] },
  { name: "workflows", path: "/workflows", heading: "Workflows", protected: true, states: ["empty", "populated"] },
  {
    name: "workflow-designer",
    path: "/workflows/designer",
    heading: "Workflow Designer",
    protected: true,
    states: ["empty", "populated"],
  },
  {
    name: "form-builder",
    path: "/forms/builder",
    heading: "Form Builder",
    protected: true,
    states: ["empty", "populated"],
  },
  {
    name: "form-fill",
    path: "/forms/fill",
    heading: "Fill Form",
    protected: true,
    states: ["empty", "populated"],
  },
  { name: "documents", path: "/documents", heading: "Documents", protected: true, states: ["empty", "populated"] },
  {
    name: "notifications",
    path: "/notifications",
    heading: "Notifications",
    protected: true,
    states: ["empty", "populated"],
  },
  {
    name: "notification-templates",
    path: "/notification-templates",
    heading: "Notification templates",
    protected: true,
    states: ["empty", "populated"],
  },
  { name: "analytics", path: "/analytics", heading: "Analytics", protected: true, states: ["empty", "populated"] },
  {
    name: "instances",
    path: "/instances",
    heading: "Workflow Instances",
    protected: true,
    states: ["empty", "populated"],
  },
  {
    name: "organizations",
    path: "/organizations",
    heading: "Organizations",
    protected: true,
    states: ["empty", "populated"],
  },
];

/** Navigates to a screen and waits for its stable heading. */
export async function openScreen(
  page: Page,
  screen: ScreenDefinition,
  state: "empty" | "populated",
): Promise<void> {
  await page.emulateMedia({ reducedMotion: "reduce" });
  if (screen.protected) {
    await setSession(page, { tenant: "tenant-a" });
  } else if (screen.sessionOnly) {
    await setSession(page, { tenant: null });
  }
  await installApiMocks(page, state === "populated");
  await page.goto(screen.path);
  await page.getByRole("heading", { name: screen.heading }).waitFor({ state: "visible" });
  await page.waitForTimeout(250);
}
