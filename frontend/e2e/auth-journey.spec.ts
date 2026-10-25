import { expect, test, type Page } from "@playwright/test";

/**
 * End-to-end user journeys exercised against the real Next.js dev server.
 * The backend is mocked at the network layer so the suite is deterministic and
 * does not require Postgres/Kafka/MinIO to be running in CI.
 */

const API = "**/api/v1/**";

/** Mocks a successful login that plants the session cookie the middleware checks. */
async function mockLogin(page: Page): Promise<void> {
  await page.route(`${API}auth/login`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "set-cookie": "awap_session=1; Path=/" },
      body: "{}",
    });
  });
}

/** Mocks the dashboard's read endpoints to return empty lists. */
async function mockDashboardReads(page: Page): Promise<void> {
  await page.route(`${API}tasks`, (route) => route.fulfill({ status: 200, contentType: "application/json", body: "[]" }));
  await page.route(`${API}workflow-instances`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
}

test.describe("registration", () => {
  test("creates a tenant and redirects back to the login page", async ({ page }) => {
    const requests: unknown[] = [];
    await page.route(`${API}tenants`, async (route) => {
      requests.push(route.request().postDataJSON());
      await route.fulfill({ status: 201, contentType: "application/json", body: "{}" });
    });

    await page.goto("/register");
    await page.getByPlaceholder("Tenant slug").fill("acme");
    await page.getByPlaceholder("Company name").fill("Acme Inc");
    await page.getByPlaceholder("Admin full name").fill("Ada Lovelace");
    await page.getByPlaceholder("Admin email").fill("ada@acme.com");
    await page.getByPlaceholder("Admin password").fill("s3cret");
    await page.getByRole("button", { name: "Create tenant" }).click();

    await expect(page).toHaveURL("/");
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({
      slug: "acme",
      name: "Acme Inc",
      adminEmail: "ada@acme.com",
    });
  });
});

test.describe("login", () => {
  test("wrong password shows an inline error and does not navigate", async ({ page }) => {
    await page.route(`${API}auth/login`, (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
    );

    await page.goto("/");
    await page.getByPlaceholder("Tenant slug").fill("acme");
    await page.getByPlaceholder("Email").fill("ada@acme.com");
    await page.getByPlaceholder("Password").fill("wrong");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByText("Login failed")).toBeVisible();
    await expect(page).toHaveURL("/");
  });

  test("login -> tenant selection -> dashboard persists state end-to-end", async ({ page }) => {
    await mockLogin(page);
    await page.route(`${API}auth/tenants`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([{ tenantId: "tenant-a", role: "TENANT_ADMIN" }]),
      }),
    );
    await mockDashboardReads(page);

    await page.goto("/");
    await page.getByPlaceholder("Tenant slug").fill("acme");
    await page.getByPlaceholder("Email").fill("ada@acme.com");
    await page.getByPlaceholder("Password").fill("s3cret");
    await page.getByRole("button", { name: "Sign in" }).click();

    // No active tenant yet: the guard routes to the tenant picker.
    await expect(page).toHaveURL(/\/select-tenant/);
    await expect(page.getByText("Select tenant")).toBeVisible();

    // Choosing a tenant persists the cookie and reloads into the dashboard.
    await page.getByRole("button", { name: /tenant-a/ }).click();
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    await expect(page.getByText("Pending tasks: 0")).toBeVisible();
    await expect(page.getByText("Active workflow instances: 0")).toBeVisible();
  });

  test("expired session redirects protected routes to the login page", async ({ page }) => {
    await page.goto("/dashboard");
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: "AWAP" })).toBeVisible();
  });
});

test.describe("tenant isolation", () => {
  test("api calls carry the active tenant header", async ({ page }) => {
    const seenHeaders: string[] = [];
    await page.route(`${API}tasks`, async (route) => {
      seenHeaders.push(route.request().headers()["x-tenant-id"] ?? "");
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    });
    await page.route(`${API}workflow-instances`, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
    );
    await page.context().addCookies([
      { name: "awap_session", value: "1", url: "http://localhost:3000" },
      { name: "awap_tenant", value: "tenant-b", url: "http://localhost:3000" },
    ]);

    await page.goto("/dashboard");

    await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    expect(seenHeaders).toContain("tenant-b");
  });
});

test.describe("CSP", () => {
  test("cross-origin fetch to an unapproved host is blocked", async ({ page }) => {
    await page.goto("/");
    const outcome = await page.evaluate(async () => {
      try {
        await fetch("https://evil.example.com/probe");
        return "allowed";
      } catch {
        return "blocked";
      }
    });
    expect(outcome).toBe("blocked");
  });
});
