import { expect, request, test, type APIRequestContext } from "@playwright/test";
import { seedPlan, type SeedPlan } from "../lib/e2eSeed";

/**
 * Real antivirus scan journey (issue #209).
 *
 * Nothing in this spec is mocked: the API talks to a real MinIO and a real ClamAV, the scanner
 * streams the uploaded object, and the status changes arrive over the real SSE stream. The spec is
 * collected only when E2E_LIVE_STACK=1 (see playwright.config.ts) because it needs the full
 * Compose profile with MinIO and ClamAV enabled.
 */
const API_URL = process.env.E2E_API_URL ?? "http://localhost:8080";
const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

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

interface DocumentDto {
  id: string;
  fileId: string;
  name: string | null;
  scanStatus: string | null;
  downloadable: boolean;
}

interface UploadSlot {
  fileId: string;
  uploadUrl: string;
  version: DocumentDto;
}

interface ScanEvent {
  fileId: string;
  status: string;
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
    `the backend must issue an XSRF token (status ${response.status()}): ${await response.text()}`,
  ).toBeTruthy();
  return ((await response.json()) as { token: string }).token;
}

async function apiHeaders(
  ctx: APIRequestContext,
  tenantId: string,
): Promise<Record<string, string>> {
  return { "X-Tenant-Id": tenantId, "X-XSRF-TOKEN": await csrfToken(ctx) };
}

async function login(ctx: APIRequestContext, plan: SeedPlan): Promise<Me> {
  // /auth/login is explicitly CSRF-ignored and unauthenticated; requesting an XSRF token first
  // would hit the authenticated /auth/csrf endpoint and return 401.
  const response = await ctx.post(`${API_URL}/api/v1/auth/login`, {
    headers: { "Content-Type": "application/json" },
    data: {
      tenantSlug: plan.slug,
      email: plan.adminEmail,
      password: plan.adminPassword,
    },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const me = await ctx.get(`${API_URL}/api/v1/auth/me`);
  expect(me.ok()).toBeTruthy();
  return (await me.json()) as Me;
}

async function listDocuments(ctx: APIRequestContext, me: Me): Promise<DocumentDto[]> {
  const response = await ctx.get(`${API_URL}/api/v1/documents`, {
    headers: await apiHeaders(ctx, me.tenantId),
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as DocumentDto[];
}

async function createSlot(
  ctx: APIRequestContext,
  me: Me,
  name: string,
  contentType: string,
  size: number,
): Promise<{ status: number; slot?: UploadSlot; detail?: string }> {
  const response = await ctx.post(`${API_URL}/api/v1/documents`, {
    headers: {
      ...(await apiHeaders(ctx, me.tenantId)),
      "Content-Type": "application/json",
    },
    data: { name, contentType, size },
  });
  if (!response.ok()) {
    return { status: response.status(), detail: await response.text() };
  }
  return { status: response.status(), slot: (await response.json()) as UploadSlot };
}

async function upload(
  ctx: APIRequestContext,
  slot: UploadSlot,
  bytes: Buffer,
  contentType: string,
): Promise<void> {
  const response = await ctx.put(slot.uploadUrl, {
    headers: { "Content-Type": contentType },
    data: bytes,
  });
  expect(response.ok(), `PUT ${slot.uploadUrl} failed with ${response.status()}`).toBeTruthy();
}

async function waitForStatus(
  ctx: APIRequestContext,
  me: Me,
  fileId: string,
  status: string,
): Promise<void> {
  await expect
    .poll(
      async () =>
        (await listDocuments(ctx, me)).find((document) => document.fileId === fileId)?.scanStatus,
      { timeout: 180_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe(status);
}

test("a clean PDF traverses QUARANTINED -> SCANNING -> CLEAN -> PROMOTED and only then downloads", async ({
  page,
  context,
}) => {
  test.setTimeout(300_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);
  const name = `clean-${Date.now()}.pdf`;

  // Load the page and start recording the real tenant-wide SSE stream before the upload so every
  // transition (QUARANTINED included) is observed.
  await page.goto(`${BASE_URL}/documents`);
  await expect(page.getByTestId("document-stream-state")).toContainText("connected", {
    timeout: 60_000,
  });
  await page.evaluate((url) => {
    const scope = window as unknown as {
      __scanEvents?: ScanEvent[];
      __scanStream?: EventSource;
    };
    scope.__scanEvents = [];
    const source = new EventSource(url, { withCredentials: true });
    source.addEventListener("scan-status", (message) => {
      try {
        scope.__scanEvents?.push(JSON.parse((message as MessageEvent).data) as ScanEvent);
      } catch {
        // A malformed event fails the assertion below rather than breaking the stream.
      }
    });
    scope.__scanStream = source;
  }, `${API_URL}/api/v1/documents/events`);

  const created = await createSlot(context.request, me, name, "application/pdf", CLEAN_PDF.length);
  expect(created.status).toBe(201);
  const slot = created.slot!;
  expect(slot.version.scanStatus).toBe("QUARANTINED");
  expect(slot.version.downloadable).toBe(false);

  // The presigned GET is refused before the scan promotes the object. The scheduler tick may
  // already have recorded a (retryable) SCAN_FAILED for the object-less row, which is also blocked.
  const pendingDownload = await context.request.get(
    `${API_URL}/api/v1/documents/${slot.fileId}/download`,
    { headers: await apiHeaders(context.request, me.tenantId) },
  );
  expect([409, 503]).toContain(pendingDownload.status());

  await upload(context.request, slot, CLEAN_PDF, "application/pdf");

  await expect
    .poll(
      async () =>
        page.evaluate((fileId) => {
          const scope = window as unknown as { __scanEvents?: ScanEvent[] };
          return (
            scope.__scanEvents
              ?.filter((event) => event.fileId === fileId)
              .map((event) => event.status) ?? []
          );
        }, slot.fileId),
      { timeout: 180_000, intervals: [500, 1_000, 2_000] },
    )
    .toEqual(expect.arrayContaining(["QUARANTINED", "SCANNING", "CLEAN", "PROMOTED"]));

  const statuses = await page.evaluate((fileId) => {
    const scope = window as unknown as { __scanEvents?: ScanEvent[] };
    return (
      scope.__scanEvents
        ?.filter((event) => event.fileId === fileId)
        .map((event) => event.status) ?? []
    );
  }, slot.fileId);
  for (const status of ["QUARANTINED", "SCANNING", "CLEAN", "PROMOTED"]) {
    expect(statuses.indexOf(status)).toBeGreaterThanOrEqual(0);
  }
  expect(statuses.indexOf("SCANNING")).toBeGreaterThan(statuses.indexOf("QUARANTINED"));
  expect(statuses.indexOf("CLEAN")).toBeGreaterThan(statuses.indexOf("SCANNING"));
  expect(statuses.indexOf("PROMOTED")).toBeGreaterThan(statuses.indexOf("CLEAN"));

  await page.reload();
  const row = page.getByTestId(`document-row-${slot.fileId}`);
  await expect(row).toBeVisible({ timeout: 60_000 });
  await expect(page.getByTestId(`scan-status-${slot.fileId}`)).toHaveText("Ready to download", {
    timeout: 60_000,
  });
  await expect(row.getByRole("button", { name: "Download" })).toBeEnabled();

  const download = await context.request.get(
    `${API_URL}/api/v1/documents/${slot.fileId}/download`,
    { headers: await apiHeaders(context.request, me.tenantId) },
  );
  expect(download.ok()).toBeTruthy();
  const { downloadUrl } = (await download.json()) as { downloadUrl: string };
  const bytes = await context.request.get(downloadUrl);
  expect(bytes.ok()).toBeTruthy();
  expect(await bytes.text()).toContain("%PDF-1.4");
});

test("EICAR reaches INFECTED and the download is forbidden", async ({ page, context }) => {
  test.setTimeout(300_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);
  const name = `eicar-${Date.now()}.pdf`;

  const created = await createSlot(context.request, me, name, "application/pdf", EICAR.length);
  expect(created.status).toBe(201);
  const slot = created.slot!;
  await upload(context.request, slot, EICAR, "application/pdf");

  await waitForStatus(context.request, me, slot.fileId, "INFECTED");

  const blocked = await context.request.get(
    `${API_URL}/api/v1/documents/${slot.fileId}/download`,
    { headers: await apiHeaders(context.request, me.tenantId) },
  );
  expect(blocked.status()).toBe(403);

  await page.goto(`${BASE_URL}/documents`);
  await expect(page.getByTestId(`scan-status-${slot.fileId}`)).toHaveText("Infected", {
    timeout: 60_000,
  });
  await expect(
    page.getByTestId(`document-row-${slot.fileId}`).getByRole("button", { name: "Download" }),
  ).toBeDisabled();
});

test("rejects oversized and unsupported uploads and keeps tenants isolated", async ({
  context,
}) => {
  test.setTimeout(300_000);
  const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
  const me = await login(context.request, plan);

  const tooLarge = await createSlot(
    context.request,
    me,
    "huge.pdf",
    "application/pdf",
    500 * 1024 * 1024 + 1,
  );
  expect(tooLarge.status).toBe(413);

  const unsupported = await createSlot(
    context.request,
    me,
    "payload.exe",
    "application/x-msdownload",
    64,
  );
  expect(unsupported.status).toBe(415);

  // Tenant A owns the file; tenant B must not be able to fetch a presigned download for it.
  const created = await createSlot(
    context.request,
    me,
    `isolated-${Date.now()}.pdf`,
    "application/pdf",
    CLEAN_PDF.length,
  );
  expect(created.status).toBe(201);
  const fileId = created.slot!.fileId;

  const suffix = `${Date.now()}`;
  const tenantB = {
    slug: `${plan.slug}-b-${suffix}`,
    name: `E2E Tenant B ${suffix}`,
    adminEmail: `admin+${suffix}@e2e.awap.dev`,
    adminPassword: "E2e-Password-123!",
    adminFullName: "E2E Tenant B Admin",
  };
  const bContext = await request.newContext({ baseURL: API_URL });
  try {
    const registration = await bContext.post(`${API_URL}/api/v1/tenants`, {
      data: tenantB,
    });
    expect([201, 409]).toContain(registration.status());
    const meB = await login(bContext, {
      slug: tenantB.slug,
      name: tenantB.name,
      adminEmail: tenantB.adminEmail,
      adminPassword: tenantB.adminPassword,
      adminFullName: tenantB.adminFullName,
    });

    const foreignDownload = await bContext.get(
      `${API_URL}/api/v1/documents/${fileId}/download`,
      { headers: await apiHeaders(bContext, meB.tenantId) },
    );
    expect([403, 404]).toContain(foreignDownload.status());

    const foreignList = await listDocuments(bContext, meB);
    expect(foreignList.some((document) => document.fileId === fileId)).toBe(false);
  } finally {
    await bContext.dispose();
  }
});
