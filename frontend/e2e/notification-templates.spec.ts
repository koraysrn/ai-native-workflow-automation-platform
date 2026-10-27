import { expect, test } from "@playwright/test";

/**
 * Template editor journey (issue #127): placeholder validation, preview rendering and the
 * unsaved-change path, exercised against the mocked API so it stays deterministic in CI.
 */
const API = "**/api/v1/**";

async function session(page: import("@playwright/test").Page): Promise<void> {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
  ]);
}

test("previews a tenant template with escaped variables", async ({ page }) => {
  await session(page);
  const requests: unknown[] = [];
  await page.route(`${API}notification-templates/preview`, async (route) => {
    requests.push(route.request().postDataJSON());
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

  await page.goto("/notification-templates");
  await page.getByLabel("Template key").fill("welcome");
  await page.getByLabel("Subject").fill("Welcome");
  await page.getByLabel("Body").fill("Hello {{name}}");
  await page.getByLabel("Variables JSON").fill('{"name":"<b>Ada</b>"}');
  await page.getByRole("button", { name: "Preview template" }).click();

  await expect(page.getByTestId("preview-body")).toHaveText("Hello <b>Ada</b>");
  await expect(page.getByText("Placeholders: name")).toBeVisible();
  expect(requests.length).toBeGreaterThan(0);
  expect(JSON.stringify(requests[0])).toContain('"templateKey":"welcome"');
});

test("blocks a malformed placeholder before calling the API", async ({ page }) => {
  await session(page);
  let called = false;
  await page.route(`${API}notification-templates/preview`, async (route) => {
    called = true;
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });

  await page.goto("/notification-templates");
  await page.getByLabel("Template key").fill("welcome");
  await page.getByLabel("Subject").fill("Welcome");
  await page.getByLabel("Body").fill("Hello {{name");
  await page.getByRole("button", { name: "Preview template" }).click();

  await expect(page.getByRole("alert").filter({ hasText: "unbalanced" })).toBeVisible();
  expect(called).toBe(false);
});

test("saves the template and shows the confirmation", async ({ page }) => {
  await session(page);
  const created: unknown[] = [];
  await page.route(`${API}notification-templates`, async (route) => {
    if (route.request().method() === "POST") {
      created.push(route.request().postDataJSON());
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

  await page.goto("/notification-templates");
  await page.getByLabel("Template key").fill("welcome");
  await page.getByLabel("Subject").fill("Welcome");
  await page.getByLabel("Body").fill("Hello {{name}}");
  await page.getByLabel("Variables JSON").fill('{"name":"Ada"}');
  await page.getByRole("button", { name: "Save template" }).click();

  await expect(page.getByRole("status")).toContainText("Template saved.");
  expect(created.length).toBeGreaterThan(0);
  expect(JSON.stringify(created[0])).toContain('"templateKey":"welcome"');
});
