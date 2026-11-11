import { expect, test, type Page } from "@playwright/test";

/**
 * Issue #200: a FILE form field uploads to the quarantine slot, shows the live scan status and
 * submits only the promoted file id. The API is mocked so the journey is deterministic.
 */
const API = "http://localhost:8080";
const SCHEMA = JSON.stringify({
  fields: [{ id: "contract", type: "FILE", required: true }],
});

async function openFillForm(page: Page): Promise<string[]> {
  const submissions: string[] = [];
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
    { name: "XSRF-TOKEN", value: "e2e-csrf", url: API },
  ]);

  await page.route(`${API}/api/v1/forms`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "f1",
          tenantId: "tenant-a",
          name: "Contract form",
          schemaJson: SCHEMA,
          version: 1,
          createdAt: "2026-11-11T00:00:00Z",
        },
      ]),
    }),
  );
  await page.route(`${API}/api/v1/forms/f1`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "f1",
        tenantId: "tenant-a",
        name: "Contract form",
        schemaJson: SCHEMA,
        version: 1,
        createdAt: "2026-11-11T00:00:00Z",
      }),
    }),
  );
  await page.route(`${API}/api/v1/workflow-instances`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.route(`${API}/api/v1/documents`, async (route) => {
    if (route.request().method() === "POST") {
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          fileId: "f-1",
          uploadUrl: `${API}/upload/f-1`,
          version: { id: "v1", fileId: "f-1", scanStatus: "QUARANTINED" },
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "v1",
          fileId: "f-1",
          version: 1,
          size: 4,
          format: "pdf",
          status: "AVAILABLE",
          createdAt: "2026-11-11T00:00:00Z",
          name: "contract.pdf",
          scanStatus: "PROMOTED",
          scanError: null,
          downloadable: true,
        },
      ]),
    });
  });
  await page.route(`${API}/upload/f-1`, (route) =>
    route.fulfill({ status: 200, headers: { "Access-Control-Allow-Origin": "*" } }),
  );
  await page.route(`${API}/api/v1/forms/f1/submissions`, async (route) => {
    submissions.push(String(route.request().postData()));
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        id: "s1",
        formId: "f1",
        workflowInstanceId: null,
        dataJson: "{}",
        submittedBy: null,
        createdAt: "2026-11-11T00:00:00Z",
        files: [
          {
            fileId: "f-1",
            name: "contract.pdf",
            size: 4,
            contentType: "application/pdf",
            scanStatus: "PROMOTED",
          },
        ],
      }),
    });
  });

  await page.goto("/forms/fill");
  await expect(page.getByRole("heading", { name: "Fill Form" })).toBeVisible();
  await page.getByLabel("Form").selectOption("f1");
  return submissions;
}

test("uploads a file, waits for promotion and submits the file id", async ({ page }) => {
  const submissions = await openFillForm(page);

  await page.getByLabel("contract file").setInputFiles({
    name: "contract.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("data"),
  });

  await expect(page.getByRole("status")).toContainText("ready to submit", { timeout: 10000 });

  await page.getByRole("button", { name: "Submit" }).click();

  await expect(page.getByText(/Submitted with 1 file/)).toBeVisible();
  await expect(page.getByText(/contract\.pdf \(PROMOTED\)/)).toBeVisible();

  expect(submissions).toHaveLength(1);
  const body = JSON.parse(submissions[0] ?? "{}") as { dataJson?: string };
  expect(JSON.parse(body.dataJson ?? "{}")).toEqual({ contract: "f-1" });
});

test("removes the uploaded reference before submitting", async ({ page }) => {
  await openFillForm(page);

  await page.getByLabel("contract file").setInputFiles({
    name: "contract.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("data"),
  });
  await expect(page.getByRole("status")).toBeVisible();

  await page.getByRole("button", { name: "Remove file" }).click();
  await expect(page.getByRole("status")).toHaveCount(0);
});
