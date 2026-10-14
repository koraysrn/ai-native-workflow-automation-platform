import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("home page has no critical or serious accessibility violations", async ({ page }) => {
  await page.goto("/");
  const results = await new AxeBuilder({ page }).analyze();
  const seriousViolations = results.violations.filter(
    (violation) => violation.impact === "critical" || violation.impact === "serious",
  );
  expect(seriousViolations).toEqual([]);
});
