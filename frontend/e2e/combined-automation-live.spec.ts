import { expect, test, type APIRequestContext } from "@playwright/test";
import { seedPlan, type SeedPlan } from "../lib/e2eSeed";

/**
 * Combined live automation scenario (issue #212).
 *
 * Real stack only: the graph runs through the workflow engine, the webhook is delivered to the
 * in-network fake webhook (HMAC signed), the document is scanned by ClamAV/MinIO, the notification
 * is persisted and the analytics aggregate is read back from PostgreSQL. Nothing is mocked.
 *
 * The spec is collected only when E2E_LIVE_STACK=1 (see playwright.config.ts), in the same CI job
 * that starts the full Compose profile including MinIO, ClamAV and the fake webhook.
 */
const API_URL = process.env.E2E_API_URL ?? "http://localhost:8080";
const WEBHOOK_URL = process.env.E2E_FAKE_WEBHOOK_URL ?? "http://localhost:9099";
const WEBHOOK_TARGET = "http://fake-webhook:9099/webhook";

const CLEAN_PDF = Buffer.from(
  "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n",
  "utf8",
);
// The standard EICAR antivirus test string; ClamAV must report it as a virus.
const EICAR = Buffer.from(
  "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*",
  "utf8",
);

interface Me {
  id: string;
  tenantId: string;
}

interface InstanceDto {
  id: string;
  state: string;
}

interface TaskDto {
  id: string;
  workflowInstanceId: string;
  status: string;
}

interface DocumentDto {
  fileId: string;
  scanStatus: string | null;
  downloadable: boolean;
}

interface LogDto {
  nodeId: string;
  nodeType: string;
  action: string;
  payload: string | null;
}

interface NotificationDto {
  businessKey: string | null;
  templateKey: string;
  status: string;
}

interface AnalyticsDto {
  eventType: string;
  count: number;
}

interface WebhookRequestDto {
  headers: Record<string, string>;
  body: string;
  status: number;
}

interface WebhookDeliveryDto {
  workflowInstanceId: string;
  status: string;
  attempts: number;
  maxAttempts: number;
}

async function csrfToken(ctx: APIRequestContext): Promise<string> {
  const state = await ctx.storageState();
  const cookie = state.cookies.find((entry) => entry.name === "XSRF-TOKEN");
  if (cookie?.value) {
    return decodeURIComponent(cookie.value);
  }
  const response = await ctx.get(`${API_URL}/api/v1/auth/csrf`);
  expect(
    response.ok(),
    `the backend must issue an XSRF token (status ${response.status()})`,
  ).toBeTruthy();
  return ((await response.json()) as { token: string }).token;
}

async function headers(ctx: APIRequestContext, tenantId: string): Promise<Record<string, string>> {
  return { "X-Tenant-Id": tenantId, "X-XSRF-TOKEN": await csrfToken(ctx) };
}

async function login(ctx: APIRequestContext, plan: SeedPlan): Promise<Me> {
  const response = await ctx.post(`${API_URL}/api/v1/auth/login`, {
    headers: { "Content-Type": "application/json" },
    data: { tenantSlug: plan.slug, email: plan.adminEmail, password: plan.adminPassword },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const me = await ctx.get(`${API_URL}/api/v1/auth/me`);
  expect(me.ok()).toBeTruthy();
  return (await me.json()) as Me;
}

async function getJson<T>(ctx: APIRequestContext, tenantId: string, path: string): Promise<T> {
  const response = await ctx.get(`${API_URL}${path}`, { headers: await headers(ctx, tenantId) });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as T;
}

async function postJson<T>(
  ctx: APIRequestContext,
  tenantId: string,
  path: string,
  data: unknown = {},
): Promise<T> {
  const response = await ctx.post(`${API_URL}${path}`, {
    headers: { ...(await headers(ctx, tenantId)), "Content-Type": "application/json" },
    data,
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as T;
}

/** Switches the fake webhook behaviour before a scenario; /reset clears the request log. */
async function controlWebhook(
  ctx: APIRequestContext,
  overrides: { failureRate?: number; failureStatus?: number; latencyMs?: number },
): Promise<void> {
  const response = await ctx.post(`${WEBHOOK_URL}/control`, { data: overrides });
  expect(response.ok(), await response.text()).toBeTruthy();
  const reset = await ctx.post(`${WEBHOOK_URL}/reset`);
  expect(reset.ok(), await reset.text()).toBeTruthy();
}

async function webhookRequests(ctx: APIRequestContext): Promise<WebhookRequestDto[]> {
  const response = await ctx.get(`${WEBHOOK_URL}/requests`);
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as WebhookRequestDto[];
}

async function uploadDocument(
  ctx: APIRequestContext,
  tenantId: string,
  name: string,
  bytes: Buffer,
): Promise<string> {
  const created = await postJson<{ fileId: string; uploadUrl: string }>(
    ctx,
    tenantId,
    "/api/v1/documents",
    { name, contentType: "application/pdf", size: bytes.length },
  );
  const uploaded = await ctx.put(created.uploadUrl, {
    headers: { "Content-Type": "application/pdf" },
    data: bytes,
  });
  expect(uploaded.ok(), await uploaded.text()).toBeTruthy();
  return created.fileId;
}

async function waitForDocumentStatus(
  ctx: APIRequestContext,
  tenantId: string,
  fileId: string,
  status: string,
): Promise<DocumentDto> {
  await expect
    .poll(
      async () =>
        (await getJson<DocumentDto[]>(ctx, tenantId, "/api/v1/documents")).find(
          (document) => document.fileId === fileId,
        )?.scanStatus,
      { timeout: 180_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe(status);
  const documents = await getJson<DocumentDto[]>(ctx, tenantId, "/api/v1/documents");
  const document = documents.find((entry) => entry.fileId === fileId);
  expect(document, `document ${fileId} must be listed`).toBeTruthy();
  return document!;
}

async function publishWorkflow(
  ctx: APIRequestContext,
  me: Me,
  name: string,
  definition: unknown,
): Promise<{ id: string }> {
  const created = await postJson<{ id: string }>(ctx, me.tenantId, "/api/v1/workflow-definitions", {
    name,
    description: "Combined automation scenario (issue #212).",
    definitionJson: JSON.stringify(definition),
  });
  await postJson(ctx, me.tenantId, `/api/v1/workflow-definitions/${created.id}/publish`);
  return created;
}

async function startWorkflow(
  ctx: APIRequestContext,
  me: Me,
  definitionId: string,
  contextJson: string,
): Promise<InstanceDto> {
  return postJson<InstanceDto>(ctx, me.tenantId, "/api/v1/workflow-instances", {
    workflowDefinitionId: definitionId,
    contextJson,
  });
}

async function waitForState(
  ctx: APIRequestContext,
  me: Me,
  instanceId: string,
  state: string,
): Promise<void> {
  await expect
    .poll(
      async () =>
        (await getJson<InstanceDto>(ctx, me.tenantId, `/api/v1/workflow-instances/${instanceId}`))
          .state,
      { timeout: 180_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe(state);
}

async function findTask(ctx: APIRequestContext, me: Me, instanceId: string): Promise<TaskDto> {
  await expect
    .poll(
      async () =>
        (await getJson<TaskDto[]>(ctx, me.tenantId, "/api/v1/tasks")).find(
          (entry) => entry.workflowInstanceId === instanceId,
        )?.id,
      { timeout: 60_000, intervals: [1_000, 2_000] },
    )
    .toBeTruthy();
  const tasks = await getJson<TaskDto[]>(ctx, me.tenantId, "/api/v1/tasks");
  const task = tasks.find((entry) => entry.workflowInstanceId === instanceId);
  expect(task, `instance ${instanceId} must create a task`).toBeTruthy();
  return task!;
}

async function approveTask(
  ctx: APIRequestContext,
  me: Me,
  taskId: string,
  comment: string,
): Promise<void> {
  await postJson(ctx, me.tenantId, `/api/v1/tasks/${taskId}/approve`, { comment });
}

async function logs(ctx: APIRequestContext, me: Me, instanceId: string): Promise<LogDto[]> {
  return getJson<LogDto[]>(ctx, me.tenantId, `/api/v1/workflow-instances/${instanceId}/logs`);
}

function mergedCount(payload: string | null): number | null {
  const match = /merged=(\d+)/.exec(payload ?? "");
  return match ? Number(match[1]) : null;
}

/**
 * DECISION -> PARALLEL(TASK + WEBHOOK + DELAY) -> MERGE -> END.
 *
 * The issue's graph also names DOCUMENT and NOTIFICATION steps. Those are not engine node types in
 * this codebase; the document pipeline and the completion notification are driven by the document
 * API and the workflow lifecycle outbox event, so the scenario exercises them around the same run.
 */
function combinedDefinition(): unknown {
  return {
    nodes: [
      { id: "start", type: "START", name: "Start" },
      { id: "decide", type: "DECISION", name: "Choose path" },
      { id: "fork", type: "PARALLEL", name: "Fork", config: { joinNodeId: "merge" } },
      {
        id: "task",
        type: "TASK",
        name: "Approve combined run",
        config: { approvers: ["role:TENANT_ADMIN"] },
      },
      {
        id: "hook",
        type: "WEBHOOK",
        name: "Notify CRM",
        config: { url: WEBHOOK_TARGET, payload: '{"workflow":"combined"}' },
      },
      { id: "wait", type: "DELAY", name: "Short wait", config: { delaySeconds: 1 } },
      { id: "merge", type: "MERGE", name: "Merge branches" },
      { id: "end", type: "END", name: "End" },
    ],
    transitions: [
      { from: "start", to: "decide" },
      { from: "decide", to: "fork", condition: "#route == 'parallel'" },
      { from: "fork", to: "task" },
      { from: "fork", to: "hook" },
      { from: "fork", to: "wait" },
      { from: "task", to: "merge" },
      { from: "hook", to: "merge" },
      { from: "wait", to: "merge" },
      { from: "merge", to: "end" },
    ],
  };
}

test("runs the combined automation scenario end to end", async ({ context }) => {
  test.setTimeout(420_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);
  await controlWebhook(context.request, { failureRate: 0, failureStatus: 500, latencyMs: 10 });

  // Real document pipeline: a clean PDF is promoted and becomes downloadable.
  const fileId = await uploadDocument(
    context.request,
    me.tenantId,
    `combined-${Date.now()}.pdf`,
    CLEAN_PDF,
  );
  const promoted = await waitForDocumentStatus(context.request, me.tenantId, fileId, "PROMOTED");
  expect(promoted.downloadable).toBe(true);

  // Combined graph with conditional routing and three parallel branches.
  const definition = await publishWorkflow(
    context.request,
    me,
    `Combined ${Date.now()}`,
    combinedDefinition(),
  );
  const instance = await startWorkflow(
    context.request,
    me,
    definition.id,
    JSON.stringify({ route: "parallel" }),
  );

  // Task branch: exactly one approval task advances through the real API.
  const task = await findTask(context.request, me, instance.id);
  await approveTask(context.request, me, task.id, "Approved by the combined scenario.");

  // Webhook branch: one HMAC-signed request reaches the in-network fake target.
  await expect
    .poll(async () => (await webhookRequests(context.request)).length, {
      timeout: 120_000,
      intervals: [1_000, 2_000],
    })
    .toBeGreaterThanOrEqual(1);
  const requests = await webhookRequests(context.request);
  const delivered = requests.find((entry) => entry.status === 200);
  expect(delivered, "the webhook target must accept the signed request").toBeTruthy();
  expect(delivered!.headers["x-awap-signature"]).toMatch(/^[0-9a-f]{64}$/);
  expect(delivered!.headers["x-awap-timestamp"]).toBeTruthy();
  expect(delivered!.body).toContain("workflow");

  // Delay branch plus merge: the instance completes only after all three branches report.
  await waitForState(context.request, me, instance.id, "COMPLETED");
  const entries = await logs(context.request, me, instance.id);
  expect(entries.some((entry) => entry.action === "NODE_FAILED")).toBe(false);

  const mergeReports = entries.filter((entry) => entry.action === "NODE_MERGED");
  expect(mergeReports, "every parallel branch must report to the merge gateway").toHaveLength(3);
  expect(mergeReports.every((entry) => (entry.payload ?? "").includes("tokens=3"))).toBe(true);
  expect(mergeReports.map((entry) => mergedCount(entry.payload))).toEqual([1, 2, 3]);

  const mergeCompleteIndex = entries.findIndex(
    (entry) => entry.nodeId === "merge" && entry.action === "NODE_COMPLETED",
  );
  const lastMergeReportIndex = entries.lastIndexOf(mergeReports.at(-1)!);
  expect(mergeCompleteIndex, "MERGE must complete after the last branch report").toBeGreaterThan(
    lastMergeReportIndex,
  );

  // Exactly one completion notification (no duplicate delivery), stable across a re-read.
  const businessKey = `WORKFLOW_COMPLETED:${instance.id}`;
  const completionNotifications = async () =>
    (
      await getJson<NotificationDto[]>(context.request, me.tenantId, "/api/v1/notifications")
    ).filter((entry) => entry.businessKey === businessKey);
  await expect
    .poll(async () => (await completionNotifications()).length, {
      timeout: 60_000,
      intervals: [1_000, 2_000],
    })
    .toBe(1);
  await new Promise((resolve) => setTimeout(resolve, 3_000));
  const notifications = await completionNotifications();
  expect(notifications).toHaveLength(1);
  expect(notifications[0]?.templateKey).toBe("workflow-completed");

  // The completed aggregate is visible in the analytics projection.
  await expect
    .poll(
      async () =>
        (
          await getJson<AnalyticsDto[]>(context.request, me.tenantId, "/api/v1/analytics/summary")
        ).find((entry) => entry.eventType === "WORKFLOW_COMPLETED")?.count ?? 0,
      { timeout: 120_000, intervals: [2_000, 3_000] },
    )
    .toBeGreaterThanOrEqual(1);
});

test("a failed parallel branch prevents merge and fails the workflow", async ({ context }) => {
  test.setTimeout(240_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);

  const definition = await publishWorkflow(context.request, me, `Branch failure ${Date.now()}`, {
    nodes: [
      { id: "start", type: "START", name: "Start" },
      { id: "fork", type: "PARALLEL", name: "Fork", config: { joinNodeId: "merge" } },
      {
        id: "task",
        type: "TASK",
        name: "Approve broken run",
        config: { approvers: ["role:TENANT_ADMIN"] },
      },
      {
        id: "hook",
        type: "WEBHOOK",
        name: "Blocked CRM",
        config: { url: "http://not-allowed.invalid/hook", payload: '{"workflow":"failure"}' },
      },
      { id: "wait", type: "DELAY", name: "Short wait", config: { delaySeconds: 1 } },
      { id: "merge", type: "MERGE", name: "Merge branches" },
      { id: "end", type: "END", name: "End" },
    ],
    transitions: [
      { from: "start", to: "fork" },
      { from: "fork", to: "task" },
      { from: "fork", to: "hook" },
      { from: "fork", to: "wait" },
      { from: "task", to: "merge" },
      { from: "hook", to: "merge" },
      { from: "wait", to: "merge" },
      { from: "merge", to: "end" },
    ],
  });
  const instance = await startWorkflow(context.request, me, definition.id, "{}");

  await waitForState(context.request, me, instance.id, "FAILED");
  const entries = await logs(context.request, me, instance.id);
  expect(entries.some((entry) => entry.action === "NODE_FAILED")).toBe(true);
  expect(
    entries.some((entry) => entry.nodeId === "merge" && entry.action === "NODE_COMPLETED"),
    "a failed branch must not let the merge gateway complete",
  ).toBe(false);
  expect(entries.filter((entry) => entry.action === "NODE_MERGED").length).toBeLessThan(3);
});

test("a webhook retry exhaustion dead-letters the delivery and fails the workflow", async ({
  context,
}) => {
  test.setTimeout(240_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);
  await controlWebhook(context.request, { failureRate: 1, failureStatus: 500, latencyMs: 10 });

  try {
    const definition = await publishWorkflow(context.request, me, `Retry DLQ ${Date.now()}`, {
      nodes: [
        { id: "start", type: "START", name: "Start" },
        {
          id: "hook",
          type: "WEBHOOK",
          name: "Flaky CRM",
          config: {
            url: WEBHOOK_TARGET,
            payload: '{"workflow":"retry"}',
            maxAttempts: 2,
            timeoutSeconds: 5,
          },
        },
        { id: "end", type: "END", name: "End" },
      ],
      transitions: [
        { from: "start", to: "hook" },
        { from: "hook", to: "end" },
      ],
    });
    const instance = await startWorkflow(context.request, me, definition.id, "{}");

    await waitForState(context.request, me, instance.id, "FAILED");
    await expect
      .poll(
        async () =>
          (
            await getJson<WebhookDeliveryDto[]>(
              context.request,
              me.tenantId,
              "/api/v1/webhook-deliveries?status=DEAD",
            )
          ).find((entry) => entry.workflowInstanceId === instance.id)?.status,
        { timeout: 120_000, intervals: [1_000, 2_000] },
      )
      .toBe("DEAD");
    const deliveries = await getJson<WebhookDeliveryDto[]>(
      context.request,
      me.tenantId,
      "/api/v1/webhook-deliveries?status=DEAD",
    );
    const dead = deliveries.find((entry) => entry.workflowInstanceId === instance.id)!;
    expect(dead.attempts).toBeGreaterThanOrEqual(2);
    expect(dead.maxAttempts).toBe(2);

    const entries = await logs(context.request, me, instance.id);
    expect(entries.some((entry) => entry.action === "NODE_FAILED")).toBe(true);
  } finally {
    await controlWebhook(context.request, { failureRate: 0, failureStatus: 500, latencyMs: 10 });
  }
});

test("an infected document is rejected and stays undownloadable", async ({ context }) => {
  test.setTimeout(300_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);

  const fileId = await uploadDocument(
    context.request,
    me.tenantId,
    `infected-${Date.now()}.pdf`,
    EICAR,
  );
  const infected = await waitForDocumentStatus(context.request, me.tenantId, fileId, "INFECTED");
  expect(infected.downloadable).toBe(false);
});

test("a paused instance is not advanced until it is resumed", async ({ context }) => {
  test.setTimeout(240_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);
  const definition = await publishWorkflow(context.request, me, `Paused ${Date.now()}`, {
    nodes: [
      { id: "start", type: "START", name: "Start" },
      {
        id: "task",
        type: "TASK",
        name: "Approve paused run",
        config: { approvers: ["role:TENANT_ADMIN"] },
      },
      { id: "end", type: "END", name: "End" },
    ],
    transitions: [
      { from: "start", to: "task" },
      { from: "task", to: "end" },
    ],
  });
  const instance = await startWorkflow(context.request, me, definition.id, "{}");
  const task = await findTask(context.request, me, instance.id);

  await postJson(context.request, me.tenantId, `/api/v1/workflow-instances/${instance.id}/pause`);
  await approveTask(context.request, me, task.id, "Approved while paused.");
  await new Promise((resolve) => setTimeout(resolve, 7_000));
  const paused = await getJson<InstanceDto>(
    context.request,
    me.tenantId,
    `/api/v1/workflow-instances/${instance.id}`,
  );
  expect(paused.state).toBe("PAUSED");

  await postJson(context.request, me.tenantId, `/api/v1/workflow-instances/${instance.id}/resume`);
  await waitForState(context.request, me, instance.id, "COMPLETED");
});
