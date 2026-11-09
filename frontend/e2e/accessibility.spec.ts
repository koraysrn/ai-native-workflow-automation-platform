import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

import {
  openScreen,
  SCREENS,
  taskFixture,
  type ScreenDefinition,
} from "./a11yFixtures";

/**
 * Accessibility coverage for every user-facing screen (issue #184).
 *
 * Each screen is scanned with axe in its empty and populated state. Only
 * `critical` and `serious` impact violations fail the build; the full report is
 * written to `a11y-report/report.json` and uploaded by CI.
 */

const REPORT_PATH = join(process.cwd(), "a11y-report", "report.json");

// The full matrix is 45 tests; axe on the designer/interactive screens can take
// longer than the default 30s when the dev server is compiling routes.
test.describe.configure({ timeout: 90_000 });

interface ReportEntry {
  screen: string;
  state: "empty" | "populated";
  path: string;
  violations: Array<{
    id: string;
    impact: string | null | undefined;
    help: string;
    nodes: string[];
  }>;
}

const report: ReportEntry[] = [];

test.beforeAll(() => {
  mkdirSync(dirname(REPORT_PATH), { recursive: true });
  writeFileSync(REPORT_PATH, "[]", "utf8");
});

test.afterAll(() => {
  writeFileSync(REPORT_PATH, JSON.stringify(report, null, 2), "utf8");
});

async function applyPopulatedState(page: import("@playwright/test").Page, screen: ScreenDefinition) {
  switch (screen.name) {
    case "tasks":
      // Invoke the element's own click handler; a pointer click can land on the
      // Next.js dev overlay even with force enabled.
      await page
        .getByRole("button", { name: taskFixture.title })
        .evaluate((element) => (element as HTMLElement).click());
      await page.getByRole("heading", { name: taskFixture.title }).waitFor();
      break;
    case "workflows":
      await page
        .getByRole("button", { name: "Start" })
        .evaluate((element) => (element as HTMLElement).click());
      await page.getByRole("heading", { name: "Start workflow" }).waitFor();
      break;
    case "workflow-designer":
      await page
        .getByRole("button", { name: "TASK", exact: true })
        .evaluate((element) => (element as HTMLElement).click());
      await page.waitForTimeout(200);
      break;
    case "form-builder":
      await page
        .getByRole("button", { name: "TEXT", exact: true })
        .evaluate((element) => (element as HTMLElement).click());
      await page.waitForTimeout(200);
      break;
    case "form-fill":
      await page.getByLabel("Form").selectOption("form-1", { force: true });
      await page.getByText("customer_name").waitFor();
      break;
    default:
      break;
  }
}

function formatViolations(
  violations: Array<{
    id: string;
    impact?: string | null;
    help: string;
    nodes: Array<{ target: unknown }>;
  }>,
): string {
  return violations
    .map(
      (violation) =>
        `${violation.id} [${violation.impact}]: ${violation.help}\n    ${violation.nodes
          .map((node) => JSON.stringify(node.target))
          .join("\n    ")}`,
    )
    .join("\n");
}

for (const screen of SCREENS) {
  for (const state of screen.states) {
    test(`${screen.name} (${state}) has no critical or serious axe violations`, async ({ page }) => {
      await openScreen(page, screen, state);
      if (state === "populated") {
        await applyPopulatedState(page, screen);
      }

      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();

      report.push({
        screen: screen.name,
        state,
        path: screen.path,
        violations: results.violations.map((violation) => ({
          id: violation.id,
          impact: violation.impact,
          help: violation.help,
          nodes: violation.nodes.map((node) => node.target.join(" ")),
        })),
      });

      const blocking = results.violations.filter(
        (violation) => violation.impact === "critical" || violation.impact === "serious",
      );
      expect(blocking, formatViolations(blocking)).toEqual([]);
    });
  }
}

for (const screen of SCREENS) {
  test(`${screen.name} keyboard focus is visible`, async ({ page }) => {
    await openScreen(page, screen, "empty");
    // Next.js dev mode injects a focusable NEXTJS-PORTAL element; it does not
    // exist in production and must not hide the first real control from the
    // keyboard walk. Tab past it, then assert the focus indicator is visible.
    for (let attempt = 0; attempt < 8; attempt += 1) {
      await page.keyboard.press("Tab");
      const tag = await page.evaluate(() => document.activeElement?.tagName ?? "");
      if (tag !== "NEXTJS-PORTAL") {
        break;
      }
    }
    const focus = await page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null;
      if (!element) {
        return null;
      }
      const style = getComputedStyle(element);
      return {
        tag: element.tagName,
        label:
          element.getAttribute("aria-label") ??
          element.textContent?.trim().slice(0, 60) ??
          "",
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        boxShadow: style.boxShadow,
      };
    });
    expect(focus, "no element received keyboard focus").not.toBeNull();
    expect(focus?.tag).not.toBe("BODY");
    const focusVisible =
      (focus?.outlineStyle !== "none" && focus?.outlineWidth !== "0px") ||
      (focus?.boxShadow !== "none" && focus?.boxShadow !== "");
    expect(focusVisible, `focus is not visible for ${JSON.stringify(focus)}`).toBe(true);
  });
}

test("login and register controls expose accessible names", async ({ page }) => {
  await openScreen(page, SCREENS[0]!, "empty");
  for (const label of ["Tenant slug", "Email", "Password"]) {
    await expect(page.getByLabel(label)).toBeVisible();
  }

  await openScreen(page, SCREENS[1]!, "empty");
  for (const label of [
    "Tenant slug",
    "Company name",
    "Admin full name",
    "Admin email",
    "Admin password",
  ]) {
    await expect(page.getByLabel(label)).toBeVisible();
  }
});

test("login failures are announced through a live region", async ({ page }) => {
  await openScreen(page, SCREENS[0]!, "empty");
  await page.route("http://localhost:8080/api/v1/auth/login", (route) =>
    route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
  );
  await page.getByLabel("Tenant slug").fill("acme");
  await page.getByLabel("Email").fill("ada@acme.com");
  await page.getByLabel("Password").fill("wrong");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator('p[aria-live="polite"]')).toContainText("Login failed");
});

test("live status regions are present on documents and notifications", async ({ page }) => {
  await openScreen(page, SCREENS.find((screen) => screen.name === "documents")!, "populated");
  await expect(page.getByTestId("document-scan-announcer")).toHaveAttribute(
    "aria-live",
    "polite",
  );

  await openScreen(
    page,
    SCREENS.find((screen) => screen.name === "notifications")!,
    "populated",
  );
  await expect(page.getByTestId("unread-badge")).toHaveAttribute("aria-live", "polite");
});
