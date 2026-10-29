import { defineConfig, devices } from "@playwright/test";

const externalStack = process.env.E2E_EXTERNAL_STACK === "1";

export default defineConfig({
  testDir: "./e2e",
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
