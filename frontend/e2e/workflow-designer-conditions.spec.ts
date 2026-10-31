import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

/**
 * Edge-level condition editing (issue #134). The API is mocked so the spec is deterministic in PR
 * mode; the same journeys run against the Compose stack in the CI e2e job.
 */
const VALIDATE_URL = /\/api\/v1\/workflow-definitions\/validate-expression$/;
const CREATE_URL = /\/api\/v1\/workflow-definitions$/;

async function openDesigner(page: Page): Promise<void> {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
  ]);
  await page.goto("/workflows/designer");
  await expect(page.getByRole("heading", { name: "Workflow Designer" })).toBeVisible();
}

async function mockValidation(page: Page, valid = true): Promise<string[]> {
  const bodies: string[] = [];
  await page.route(VALIDATE_URL, async (route) => {
    bodies.push(String(route.request().postData()));
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        valid
          ? { valid: true, errors: [] }
          : {
              valid: false,
              errors: [{ field: "expression", message: "Expression is invalid." }],
            },
      ),
    });
  });
  return bodies;
}

async function addNode(page: Page, type: string): Promise<void> {
  await page.getByRole("button", { name: type, exact: true }).click();
}

async function connectNodes(page: Page, from: string, to: string): Promise<void> {
  const edges = page.locator(".react-flow__edge");
  const before = await edges.count();
  const fromHandle = page
    .locator(".react-flow__node", { hasText: from })
    .first()
    .locator(".react-flow__handle.source");
  const toHandle = page
    .locator(".react-flow__node", { hasText: to })
    .first()
    .locator(".react-flow__handle.target");
  const source = await fromHandle.boundingBox();
  const target = await toHandle.boundingBox();
  if (!source || !target) {
    throw new Error(`Could not connect ${from} to ${to}: handle not visible.`);
  }
  await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
  await page.mouse.down();
  await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2, {
    steps: 12,
  });
  await page.mouse.up();
  await expect(edges).toHaveCount(before + 1);
}

/** Opens the inspector through the keyboard-accessible transition list. */
async function openEdge(page: Page, from: string, to: string): Promise<void> {
  await page
    .getByRole("button", { name: new RegExp(`Edit condition from ${from} to ${to}`) })
    .click();
  await expect(page.getByRole("heading", { name: "Edge condition" })).toBeVisible();
}

async function buildCondition(
  page: Page,
  variable: string,
  operator: string,
  value: string,
): Promise<void> {
  await page.getByLabel("Variable").selectOption(variable);
  await page.getByLabel("Operator").selectOption(operator);
  await page.getByLabel("Value").fill(value);
  await page.getByRole("button", { name: "Insert condition" }).click();
}

test("creates a DECISION branch with two conditions and saves them", async ({ page }) => {
  await mockValidation(page);
  const payloads: string[] = [];
  await page.route(CREATE_URL, async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    payloads.push(String(route.request().postData()));
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        id: "w1",
        tenantId: "tenant-a",
        name: "Expense branch",
        description: null,
        status: "DRAFT",
        currentVersion: 1,
      }),
    });
  });

  await openDesigner(page);
  await addNode(page, "START");
  await addNode(page, "DECISION");
  await addNode(page, "END");
  await addNode(page, "TASK");

  await connectNodes(page, "START", "DECISION");
  await openEdge(page, "START", "DECISION");
  await buildCondition(page, "amount", ">=", "100");
  await expect(page.getByTestId("condition-badge").first()).toHaveText("IF: #amount >= 100");

  await connectNodes(page, "DECISION", "TASK");
  await openEdge(page, "DECISION", "TASK");
  await buildCondition(page, "role", "hasRole", "MANAGER");
  await expect(page.getByTestId("condition-badge").nth(1)).toHaveText(
    "IF: #fn.hasRole(#role, 'MANAGER')",
  );

  await expect(page.getByRole("heading", { name: "Legend" })).toBeVisible();
  await expect(page.getByText(/Conditional transition evaluated as a SpEL expression/)).toBeVisible();

  await page.getByLabel("Workflow name").fill("Expense branch");
  await page.getByRole("button", { name: "Save draft" }).click();

  await expect(page.getByRole("status").filter({ hasText: "Draft saved" })).toBeVisible();
  expect(payloads).toHaveLength(1);
  const request = JSON.parse(payloads[0] ?? "{}") as { definitionJson?: string };
  const definition = JSON.parse(request.definitionJson ?? "{}") as {
    transitions: Array<{ condition?: string }>;
  };
  const conditions = definition.transitions
    .map((transition) => transition.condition)
    .filter((condition): condition is string => typeof condition === "string");
  expect(conditions).toEqual(["#amount >= 100", "#fn.hasRole(#role, 'MANAGER')"]);
});

test("blocks saving while an edge condition is malformed", async ({ page }) => {
  const validationCalls = await mockValidation(page);
  await page.route(CREATE_URL, async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    await route.fulfill({ status: 500, body: "should not save" });
  });

  await openDesigner(page);
  await addNode(page, "START");
  await addNode(page, "END");
  await connectNodes(page, "START", "END");
  await openEdge(page, "START", "END");

  await page.getByLabel("Condition expression").fill("#amount > (100");
  await expect(
    page.getByRole("alert").filter({ hasText: "Expression is invalid" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Save draft" })).toBeDisabled();
  await expect(page.getByText(/Fix invalid edge conditions to enable saving/)).toBeVisible();
  expect(validationCalls).toHaveLength(0);
});

test("supports keyboard-only editing and passes the axe check", async ({ page }) => {
  await mockValidation(page);
  await page.route(CREATE_URL, (route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        id: "w1",
        tenantId: "tenant-a",
        name: "Keyboard flow",
        description: null,
        status: "DRAFT",
        currentVersion: 1,
      }),
    }),
  );

  await openDesigner(page);
  await addNode(page, "START");
  await addNode(page, "END");
  await connectNodes(page, "START", "END");

  const transitionButton = page.getByRole("button", {
    name: /Edit condition from START to END/,
  });
  await transitionButton.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Edge condition" })).toBeVisible();

  const textarea = page.getByLabel("Condition expression");
  await textarea.focus();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("#amount > 100");
  await expect(textarea).toHaveValue("#amount > 100");
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { name: "Edge condition" })).toHaveCount(0);

  await page.getByLabel("Workflow name").fill("Keyboard flow");
  await page.getByRole("button", { name: "Save draft" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status").filter({ hasText: "Draft saved" })).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  const serious = results.violations.filter(
    (violation) => violation.impact === "critical" || violation.impact === "serious",
  );
  expect(serious).toEqual([]);
});
