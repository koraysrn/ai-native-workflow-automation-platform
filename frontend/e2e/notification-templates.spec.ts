import { expect, test, type Page } from "@playwright/test";

/**
 * Template editor journey (issue #127): preview with escaped variables, placeholder validation and
 * saving. Fields are filled by id inside a retry loop because the Next.js dev server can re-hydrate
 * the client tree right after navigation, which would otherwise drop early input.
 */
const API = "**/api/v1/**";

async function mockTemplateList(page: Page, templates: unknown[] = []): Promise<void> {
  await page.route(`${API}notification-templates`, async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(templates),
      });
      return;
    }
    await route.fallback();
  });
}

async function openEditor(page: Page): Promise<void> {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
  ]);
  await page.goto("/notification-templates");
  await expect(page.getByRole("heading", { name: "Notification templates" })).toBeVisible();
}

async function fillEditor(
  page: Page,
  options: { body?: string; variables?: string } = {},
): Promise<void> {
  await page.locator("#template-key").fill("welcome");
  await page.locator("#template-subject").fill("Welcome");
  await page.locator("#template-body").fill(options.body ?? "Hello {{name}}");
  await page.locator("#template-variables").fill(options.variables ?? '{"name":"Ada"}');
}

test("previews a tenant template with escaped variables", async ({ page }) => {
  const bodies: string[] = [];
  await mockTemplateList(page);
  await page.route(`${API}notification-templates/preview`, async (route) => {
    bodies.push(String(route.request().postData()));
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        subject: "Welcome",
        body: "Hello <b>Ada</b>",
        placeholders: ["name"],
      }),
    });
  });

  await openEditor(page);
  await expect(async () => {
    await fillEditor(page);
    await page.getByRole("button", { name: "Preview template" }).click();
    await expect(page.getByTestId("preview-body")).toHaveText("Hello <b>Ada</b>");
  }).toPass({ timeout: 15_000 });

  await expect(page.getByText("Placeholders: name")).toBeVisible();
  expect(bodies.length).toBeGreaterThan(0);
  expect(bodies.some((body) => body.includes('"body":"Hello {{name}}"'))).toBe(true);
});

test("blocks a malformed placeholder before calling the API", async ({ page }) => {
  let called = false;
  await mockTemplateList(page);
  await page.route(`${API}notification-templates/preview`, async (route) => {
    called = true;
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });

  await openEditor(page);
  await expect(async () => {
    await fillEditor(page, { body: "Hello {{name" });
    await page.getByRole("button", { name: "Preview template" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "unbalanced" })).toBeVisible();
  }).toPass({ timeout: 15_000 });

  expect(called).toBe(false);
});

test("saves the template and shows the confirmation", async ({ page }) => {
  const created: string[] = [];
  await page.route(`${API}notification-templates`, async (route) => {
    if (route.request().method() === "POST") {
      created.push(String(route.request().postData()));
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          id: "t1",
          templateKey: "welcome",
          locale: "en",
          subject: "Welcome",
          body: "Hello {{name}}",
          version: 0,
          active: true,
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        }),
      });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await openEditor(page);
  await expect(async () => {
    await fillEditor(page);
    await page.getByRole("button", { name: "Save template" }).click();
    await expect(page.getByRole("status")).toContainText("Template saved.");
  }).toPass({ timeout: 15_000 });

  expect(created.length).toBeGreaterThan(0);
  expect(created.some((body) => body.includes('"subject":"Welcome"'))).toBe(true);
});

test("edits an existing template with its optimistic version", async ({ page }) => {
  const existing = {
    id: "t1",
    templateKey: "welcome",
    locale: "en",
    subject: "Welcome",
    body: "Hello {{name}}",
    version: 3,
    active: true,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
  const updates: string[] = [];
  await mockTemplateList(page, [existing]);
  await page.route(`${API}notification-templates/t1`, async (route) => {
    if (route.request().method() === "PUT") {
      updates.push(String(route.request().postData()));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...existing, body: "Updated {{name}}", version: 4 }),
      });
      return;
    }
    await route.fallback();
  });

  await openEditor(page);
  await expect(async () => {
    await page.getByRole("button", { name: /welcome · en · v3 · active/ }).click();
    await page.locator("#template-body").fill("Updated {{name}}");
    await page.getByRole("button", { name: "Save template" }).click();
    await expect(page.getByRole("status")).toContainText("Template saved.");
  }).toPass({ timeout: 15_000 });

  expect(updates.some((body) => body.includes('"expectedVersion":3'))).toBe(true);
  expect(updates.some((body) => body.includes('"body":"Updated {{name}}"'))).toBe(true);
});
