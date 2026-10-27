import { request } from "@playwright/test";
import { seedPlan, seedTenant, waitForReady, type FetchLike } from "../lib/e2eSeed";

const SLEEP = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Waits for the stack and seeds the E2E tenant when E2E_LIVE_STACK=1 (the CI e2e job).
 * The default local run keeps the mocked specs fast and dependency-free.
 */
export default async function globalSetup(): Promise<void> {
  if (process.env.E2E_LIVE_STACK !== "1") {
    return;
  }
  const baseUrl = process.env.E2E_BASE_URL ?? "http://localhost:3000";
  const apiUrl = process.env.E2E_API_URL ?? "http://localhost:8080";
  const context = await request.newContext();
  const fetchImpl: FetchLike = async (url, init) => {
    const response = await context.fetch(url, init);
    return {
      ok: response.ok(),
      status: response.status(),
      text: () => response.text(),
    };
  };
  try {
    await waitForReady(fetchImpl, `${apiUrl}/actuator/health`, {
      attempts: 60,
      delayMs: 5000,
      sleep: SLEEP,
    });
    await waitForReady(fetchImpl, baseUrl, { attempts: 60, delayMs: 5000, sleep: SLEEP });
    const plan = seedPlan(process.env.E2E_SEED_SUFFIX ?? "local");
    const seeded = await seedTenant(fetchImpl, apiUrl, plan);
    console.log(`E2E seed ${seeded.created ? "created" : "reused"} tenant ${seeded.slug}.`);
  } finally {
    await context.dispose();
  }
}
