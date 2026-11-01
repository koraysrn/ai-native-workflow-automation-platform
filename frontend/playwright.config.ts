import { defineConfig, devices } from "@playwright/test";

const externalStack = process.env.E2E_EXTERNAL_STACK === "1";
// The live product journey (issue #158) needs the Compose backend, seeded tenant and Kafka.
// Keep it out of the dependency-free PR run unless the stack explicitly enabled it.
const liveStack = process.env.E2E_LIVE_STACK === "1";

export default defineConfig({
  testDir: "./e2e",
  ...(liveStack ? {} : { testIgnore: ["**/live-journey.spec.ts"] }),
  fullyParallel: false,
  // One worker keeps the seeded fixtures deterministic; shard only once the suite is stable.
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  globalSetup: "./e2e/global-setup.ts",
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  ...(externalStack
    ? {}
    : {
        webServer: {
          command: "pnpm dev",
          url: "http://localhost:3000",
          reuseExistingServer: !process.env.CI,
        },
      }),
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
