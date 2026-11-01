import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { seedPlan } from "../lib/e2eSeed";

/**
 * Live product journey (issue #158). Unlike the mocked specs, every assertion below runs against
 * the real Compose stack: real login, real workflow engine, real task decision, real document scan
 * projection and real analytics aggregates.
 *
 * The spec is collected only when E2E_LIVE_STACK=1 (see playwright.config.ts). The seeded tenant
 * comes from the same seedPlan() used by e2e/global-setup.ts, so the credentials and slug always
 * match the tenant the setup created.
 */

const API_URL = process.env.E2E_API_URL ?? "http://localhost:8080";

interface CurrentUser {
  id: string;
  tenantId: string;
  email: string;
}

interface TaskDto {
  id: string;
  title: string;
  status: string;
}

interface WorkflowDefinitionDto {
  id: string;
  name: string;
  status: string;
  currentVersion: number;
}

interface WorkflowInstanceDto {
  id: string;
  workflowDefinitionId: string;
  state: string;
}

interface DocumentVersionDto {
  id: string;
  fileId: string;
  name: string | null;
  scanStatus: string | null;
  downloadable: boolean;
}

interface AnalyticsAggregateDto {
  day: string;
  eventType: string;
  count: number;
}

/** Reads the XSRF-TOKEN cookie that the backend sets on the first authenticated GET. */
async function csrfToken(context: BrowserContext): Promise<string> {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const cookies = await context.cookies();
    const cookie = cookies.find((entry) => entry.name === "XSRF-TOKEN");
    if (cookie?.value) {
      return decodeURIComponent(cookie.value);
    }
    await context.request.get(`${API_URL}/api/v1/auth/me`);
  }
  throw new Error("The backend did not issue the XSRF-TOKEN cookie.");
}

async function apiHeaders(page: Page, tenantId: string): Promise<Record<string, string>> {
  return {
    "X-Tenant-Id": tenantId,
    "X-XSRF-TOKEN": await csrfToken(page.context()),
  };
}

async function apiGet<T>(page: Page, path: string, tenantId: string): Promise<T> {
  const response = await page.request.get(`${API_URL}${path}`, {
    headers: await apiHeaders(page, tenantId),
  });
  if (!response.ok()) {
    throw new Error(`GET ${path} failed with ${response.status()}: ${await response.text()}`);
  }
  return (await response.json()) as T;
}

async function apiPost<T>(page: Page, path: string, tenantId: string, body: unknown): Promise<T> {
  const response = await page.request.post(`${API_URL}${path}`, {
    headers: {
      ...(await apiHeaders(page, tenantId)),
      "Content-Type": "application/json",
    },
    data: body,
  });
  if (!response.ok()) {
    throw new Error(`POST ${path} failed with ${response.status()}: ${await response.text()}`);
  }
  return (await response.json()) as T;
}

test("completes the live product journey against the real API", async ({ page }) => {
  test.setTimeout(240_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");

  // 1. Real login through the browser form; no route interception anywhere in this spec.
  await page.goto("/");
  await page.getByPlaceholder("Tenant slug").fill(plan.slug);
  await page.getByPlaceholder("Email").fill(plan.adminEmail);
  await page.getByPlaceholder("Password").fill(plan.adminPassword);
  await page.getByRole("button", { name: "Sign in" }).click();

  // The single seeded membership is auto-selected by /select-tenant.
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 60_000 });
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible({
    timeout: 30_000,
  });

  const meResponse = await page.request.get(`${API_URL}/api/v1/auth/me`);
  expect(meResponse.ok(), "the browser session must authenticate against /auth/me").toBeTruthy();
  const me = (await meResponse.json()) as CurrentUser;
  const tenantId = me.tenantId;

  // 2. Dashboard reflects the real API counts for the seeded tenant.
  const initialTasks = await apiGet<TaskDto[]>(page, "/api/v1/tasks", tenantId);
  const initialInstances = await apiGet<WorkflowInstanceDto[]>(
    page,
    "/api/v1/workflow-instances",
    tenantId,
  );
  await expect(page.getByText(`Pending tasks: ${initialTasks.length}`)).toBeVisible();
  await expect(
    page.getByText(`Active workflow instances: ${initialInstances.length}`),
  ).toBeVisible();

  // 3. Publish a tiny workflow and start it through the real UI.
  const suffix = `${plan.slug}-${Date.now()}`;
  const workflowName = `Live journey ${suffix}`;
  const definition = await apiPost<WorkflowDefinitionDto>(
    page,
    "/api/v1/workflow-definitions",
    tenantId,
    {
      name: workflowName,
      description: "Started by the live Playwright journey.",
      definitionJson: JSON.stringify({
        nodes: [
          { id: "start", type: "START", name: "Start" },
          { id: "end", type: "END", name: "End" },
        ],
        transitions: [{ from: "start", to: "end" }],
      }),
    },
  );
  const published = await apiPost<WorkflowDefinitionDto>(
    page,
    `/api/v1/workflow-definitions/${definition.id}/publish`,
    tenantId,
    {},
  );
  expect(published.status).toBe("PUBLISHED");

  await page.goto("/workflows");
  await expect(page.getByRole("heading", { name: "Workflows" })).toBeVisible();
  const workflowRow = page.getByRole("listitem").filter({ hasText: workflowName });
  await expect(workflowRow).toContainText("PUBLISHED");
  await workflowRow.getByRole("button", { name: "Start" }).click();
  const startDialog = page.getByRole("heading", { name: "Start workflow" }).locator("..");
  await page.getByPlaceholder("Context JSON").fill('{"source":"live-e2e"}');
  await startDialog.getByRole("button", { name: "Start", exact: true }).click();
  await expect(startDialog).toBeHidden({ timeout: 30_000 });

  // START -> END completes synchronously in the engine, which also emits WORKFLOW_COMPLETED.
  await expect
    .poll(
      async () => {
        const instances = await apiGet<WorkflowInstanceDto[]>(
          page,
          "/api/v1/workflow-instances",
          tenantId,
        );
        return instances.find((instance) => instance.workflowDefinitionId === definition.id)?.state;
      },
      { timeout: 60_000, intervals: [1_000, 2_000] },
    )
    .toBe("COMPLETED");

  await page.goto("/instances");
  await expect(page.getByRole("heading", { name: "Workflow Instances" })).toBeVisible();
  const instanceRow = page.getByRole("listitem").filter({ hasText: definition.id });
  await expect(instanceRow).toContainText("COMPLETED");

  // 4. Create an assigned task via the API and approve it through the real UI.
  const taskTitle = `Live task ${suffix}`;
  const task = await apiPost<TaskDto>(page, "/api/v1/tasks", tenantId, {
    title: taskTitle,
    description: "Approved by the live Playwright journey.",
    assigneeId: me.id,
  });
  expect(task.status).toBe("ASSIGNED");

  await page.goto("/tasks");
  await expect(page.getByRole("heading", { name: "Tasks" })).toBeVisible();
  const taskRow = page.getByRole("listitem").filter({ hasText: taskTitle });
  await expect(taskRow).toContainText("ASSIGNED");
  await taskRow.getByRole("button", { name: taskTitle }).click();
  await expect(page.getByRole("heading", { name: taskTitle })).toBeVisible();
  await page.getByPlaceholder("Comment").fill("Approved by the live E2E journey.");
  await page.getByRole("button", { name: "Approve", exact: true }).click();
  await expect(taskRow).toContainText("APPROVED", { timeout: 30_000 });
  await expect
    .poll(
      async () => {
        const tasks = await apiGet<TaskDto[]>(page, "/api/v1/tasks", tenantId);
        return tasks.find((entry) => entry.id === task.id)?.status;
      },
      { timeout: 30_000, intervals: [1_000, 2_000] },
    )
    .toBe("APPROVED");

  // 5. Register a document through the real API. The E2E stack runs without the opt-in MinIO
  //    image and with bucket provisioning disabled, so the API records the real QUARANTINED scan
  //    projection (the upload-to-PROMOTED path is covered by document-scan-live.spec.ts).
  const documentName = `live-document-${suffix}.pdf`;
  const document = await apiPost<{
    fileId: string;
    uploadUrl: string;
    version: DocumentVersionDto;
  }>(page, "/api/v1/documents", tenantId, {
    name: documentName,
    contentType: "application/pdf",
    size: 128,
  });
  expect(document.version.scanStatus).toBe("QUARANTINED");
  expect(document.version.downloadable).toBe(false);

  const documents = await apiGet<DocumentVersionDto[]>(page, "/api/v1/documents", tenantId);
  const listedDocument = documents.find((entry) => entry.fileId === document.fileId);
  expect(listedDocument?.scanStatus).toBe("QUARANTINED");
  expect(listedDocument?.downloadable).toBe(false);

  await page.goto("/documents");
  await expect(page.getByRole("heading", { name: "Documents" })).toBeVisible();
  const documentRow = page.getByTestId(`document-row-${document.fileId}`);
  await expect(documentRow).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId(`scan-status-${document.fileId}`)).toHaveText("Quarantined");
  await expect(documentRow.getByRole("button", { name: "Download" })).toBeDisabled();

  // 6. The completed workflow must reach the analytics aggregate and the analytics page must
  //    render that real value. The Compose job enables Kafka/outbox delivery for this assertion.
  let summary: AnalyticsAggregateDto[] = [];
  await expect
    .poll(
      async () => {
        summary = await apiGet<AnalyticsAggregateDto[]>(
          page,
          "/api/v1/analytics/summary",
          tenantId,
        );
        return summary.find((entry) => entry.eventType === "WORKFLOW_COMPLETED")?.count ?? 0;
      },
      { timeout: 90_000, intervals: [2_000, 3_000] },
    )
    .toBeGreaterThanOrEqual(1);
  const completedCount =
    summary.find((entry) => entry.eventType === "WORKFLOW_COMPLETED")?.count ?? 0;

  await page.goto("/analytics");
  await expect(page.getByRole("heading", { name: "Analytics" })).toBeVisible();
  const renderedCount = Number(
    await page.getByTestId("analytics-completed-workflows-value").textContent(),
  );
  expect(renderedCount).toBeGreaterThanOrEqual(completedCount);
  await expect(page.getByTestId("analytics-row-WORKFLOW_COMPLETED")).toBeVisible();
});
