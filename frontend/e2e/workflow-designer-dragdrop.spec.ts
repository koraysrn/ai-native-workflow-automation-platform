import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

/**
 * Palette drag-and-drop, keyboard editing, undo/redo and graph validation
 * (issue #198). The create endpoint is mocked so the spec is deterministic in PR
 * mode and still exercises the mocked API in the live Compose stack.
 */
const CREATE_URL = /\/api\/v1\/workflow-definitions$/;
const VALIDATE_URL = /\/api\/v1\/workflow-definitions\/validate-expression$/;

async function openDesigner(page: Page): Promise<void> {
  await page.context().addCookies([
    { name: "awap_session", value: "1", url: "http://localhost:3000" },
    { name: "awap_tenant", value: "tenant-a", url: "http://localhost:3000" },
    // A readable CSRF cookie keeps the mocked POST from bootstrapping against the API.
    { name: "XSRF-TOKEN", value: "e2e-csrf", url: "http://localhost:8080" },
  ]);
  await page.route(VALIDATE_URL, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ valid: true, errors: [] }),
    }),
  );
  await page.goto("/workflows/designer");
  await expect(page.getByRole("heading", { name: "Workflow Designer" })).toBeVisible();
}

async function mockCreate(page: Page): Promise<string[]> {
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
        id: "w-dnd",
        tenantId: "tenant-a",
        name: "Drag and drop flow",
        description: null,
        status: "DRAFT",
        currentVersion: 1,
      }),
    });
  });
  return payloads;
}

async function selectOptionValue(
  page: Page,
  label: "Transition source" | "Transition target",
  index: number,
): Promise<string> {
  const option = page.getByLabel(label).locator("option").nth(index);
  const value = await option.getAttribute("value");
  if (!value) {
    throw new Error(`No option at index ${index} for ${label}.`);
  }
  await page.getByLabel(label).selectOption(value);
  return value;
}

test("drags a node onto the canvas, edits with the keyboard and saves", async ({ page }) => {
  const payloads = await mockCreate(page);
  await openDesigner(page);

  const canvas = page.getByTestId("designer-canvas");
  const nodes = page.locator(".react-flow__node");
  const edges = page.locator(".react-flow__edge");

  await page.getByRole("button", { name: "START", exact: true }).dragTo(canvas);
  await expect(nodes).toHaveCount(1);
  await expect(page.getByRole("status").filter({ hasText: "START node added" })).toBeVisible();

  const endButton = page.getByRole("button", { name: "END", exact: true });
  await endButton.focus();
  await page.keyboard.press("Enter");
  await expect(nodes).toHaveCount(2);

  // Duplicate and delete the selected node with the keyboard.
  await nodes.first().click();
  await page.keyboard.press("ControlOrMeta+d");
  await expect(nodes).toHaveCount(3);
  await page.keyboard.press("Delete");
  await expect(nodes).toHaveCount(2);

  const source = await selectOptionValue(page, "Transition source", 1);
  const target = await selectOptionValue(page, "Transition target", 2);
  expect(source).not.toBe(target);
  await page.getByRole("button", { name: "Add transition" }).click();
  await expect(edges).toHaveCount(1);

  await page.getByRole("button", { name: "Undo" }).click();
  await expect(edges).toHaveCount(0);
  await page.getByRole("button", { name: "Redo" }).click();
  await expect(edges).toHaveCount(1);

  await page.getByLabel("Workflow name").fill("Drag and drop flow");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Draft saved" })).toBeVisible();
  expect(payloads).toHaveLength(1);

  const definition = JSON.parse(
    (JSON.parse(payloads[0] ?? "{}") as { definitionJson?: string }).definitionJson ?? "{}",
  ) as { nodes: Array<{ type: string }>; transitions: Array<{ from: string; to: string }> };
  expect(definition.nodes.map((node) => node.type)).toEqual(["START", "END"]);
  expect(definition.transitions).toHaveLength(1);

  const results = await new AxeBuilder({ page }).analyze();
  const serious = results.violations.filter(
    (violation) => violation.impact === "critical" || violation.impact === "serious",
  );
  expect(serious).toEqual([]);
});

test("drops at the pointer after zooming and panning the canvas", async ({ page }) => {
  await mockCreate(page);
  await openDesigner(page);

  const canvas = page.getByTestId("designer-canvas");
  await page.locator(".react-flow__controls-zoomin").click();
  await page.locator(".react-flow__controls-zoomin").click();

  // Pan the pane, then drop at an explicit point inside the canvas.
  const canvasBox = await canvas.boundingBox();
  if (!canvasBox) {
    throw new Error("The designer canvas is not visible.");
  }
  await page.mouse.move(canvasBox.x + 560, canvasBox.y + 380);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + 460, canvasBox.y + 300, { steps: 8 });
  await page.mouse.up();

  await page.getByRole("button", { name: "FORM", exact: true }).dragTo(canvas, {
    targetPosition: { x: 320, y: 240 },
  });

  const node = page.locator(".react-flow__node").first();
  await expect(node).toHaveCount(1);
  const nodeBox = await node.boundingBox();
  expect(nodeBox).not.toBeNull();
  if (!nodeBox) {
    throw new Error("The dropped node has no bounding box.");
  }
  // React Flow positions a node by its top-left corner, which is exactly the
  // flow coordinate under the pointer after the zoom and the pan.
  expect(Math.abs(nodeBox.x - (canvasBox.x + 320))).toBeLessThanOrEqual(40);
  expect(Math.abs(nodeBox.y - (canvasBox.y + 240))).toBeLessThanOrEqual(40);
});

test("ignores a drop outside the canvas", async ({ page }) => {
  await mockCreate(page);
  await openDesigner(page);

  await page
    .getByRole("button", { name: "TASK", exact: true })
    .dragTo(page.getByRole("heading", { name: "Workflow Designer" }));

  await expect(page.locator(".react-flow__node")).toHaveCount(0);
});

test("rejects a cycle and a graph without START before saving", async ({ page }) => {
  const payloads = await mockCreate(page);
  await openDesigner(page);

  await page.getByRole("button", { name: "TASK", exact: true }).click();
  await page.getByRole("button", { name: "END", exact: true }).click();

  await selectOptionValue(page, "Transition source", 1);
  await selectOptionValue(page, "Transition target", 2);
  await page.getByRole("button", { name: "Add transition" }).click();
  await expect(page.locator(".react-flow__edge")).toHaveCount(1);

  await selectOptionValue(page, "Transition source", 2);
  await selectOptionValue(page, "Transition target", 1);
  await page.getByRole("button", { name: "Add transition" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "cycle" })).toBeVisible();
  await expect(page.locator(".react-flow__edge")).toHaveCount(1);

  await page.getByLabel("Workflow name").fill("Invalid graph");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "exactly one START" })).toBeVisible();
  expect(payloads).toHaveLength(0);
});
