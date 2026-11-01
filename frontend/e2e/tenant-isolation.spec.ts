import { expect, test } from "@playwright/test";

/**
 * Asserts the browser client always scopes tenant calls and never renders data when the backend
 * rejects the request as cross-tenant (issue #128).
 */
const API = "**/api/v1/**";

test("tenant-scoped reads send the active tenant header", async ({ page }) => {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
  ]);
  const seen: Record<string, string> = {};
  await page.route(`${API}tasks`, async (route) => {
    seen["x-tenant-id"] = route.request().headers()["x-tenant-id"] ?? "";
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await page.route(`${API}workflow-instances`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  // React Query issues the read asynchronously; poll until the intercepted request lands.
  await expect.poll(() => seen["x-tenant-id"]).toBe("tenant-a");
});

test("a cross-tenant rejection renders no foreign rows", async ({ page }) => {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-b", url: "http://localhost:3000" },
  ]);
  const headers: Record<string, string> = {};
  await page.route(`${API}tasks`, async (route) => {
    headers["x-tenant-id"] = route.request().headers()["x-tenant-id"] ?? "";
    await route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ status: 404, title: "The requested resource was not found." }),
    });
  });

  await page.goto("/tasks");

  await expect(page.getByRole("heading", { name: "Tasks" })).toBeVisible();
  await expect.poll(() => headers["x-tenant-id"]).toBe("tenant-b");
  await expect(page.locator("main ul li")).toHaveCount(0);
});
