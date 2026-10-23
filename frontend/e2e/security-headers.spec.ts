import { expect, test } from "@playwright/test";

/**
 * Asserts that the CSP connect-src directive only allows the configured API
 * origins and never an arbitrary foreign host, and that the common security
 * headers are present on every response.
 */
test("security headers block foreign connect origins", async ({ request }) => {
  const response = await request.get("/");
  const csp = response.headers()["content-security-policy"] ?? "";

  expect(csp).toContain("connect-src");
  expect(csp).not.toContain("https://evil.example.com");
  expect(csp).toContain("frame-ancestors 'none'");

  expect(response.headers()["x-content-type-options"]).toBe("nosniff");
  expect(response.headers()["x-frame-options"]).toBe("DENY");
  expect(response.headers()["referrer-policy"]).toBe(
    "strict-origin-when-cross-origin",
  );
});
