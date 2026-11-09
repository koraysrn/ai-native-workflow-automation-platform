import { expect, test } from "@playwright/test";

/**
 * Asserts the per-request nonce policy (issue #183): no `unsafe-inline`, no
 * `unsafe-eval`, a CSP reporting endpoint, and the common security headers.
 */
test("security headers use a nonce and block foreign connect origins", async ({ request }) => {
  const response = await request.get("/");
  const csp = response.headers()["content-security-policy"] ?? "";

  expect(csp).toContain("script-src 'self'");
  expect(csp).toMatch(/'nonce-[A-Za-z0-9_-]{16,}'/);
  expect(csp).toContain("'strict-dynamic'");
  expect(csp).not.toContain("unsafe-inline");
  expect(csp).not.toContain("unsafe-eval");
  expect(csp).toContain("connect-src");
  expect(csp).not.toContain("https://evil.example.com");
  expect(csp).toContain("report-uri /api/csp-report");
  expect(csp).toContain("frame-ancestors 'none'");

  expect(response.headers()["report-to"]).toContain("/api/csp-report");
  expect(response.headers()["reporting-endpoints"]).toContain("/api/csp-report");
  expect(response.headers()["x-content-type-options"]).toBe("nosniff");
  expect(response.headers()["x-frame-options"]).toBe("DENY");
  expect(response.headers()["referrer-policy"]).toBe("strict-origin-when-cross-origin");
});

test("inline scripts without a nonce are blocked and eval is disabled", async ({ page }) => {
  // page.evaluate runs through CDP and bypasses CSP, so the injection has to be
  // part of the parsed HTML response for the browser's CSP enforcement to apply.
  await page.route("**/*", async (route) => {
    if (route.request().resourceType() !== "document") {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const csp = response.headers()["content-security-policy"] ?? "";
    const nonce = /'nonce-([A-Za-z0-9_-]+)'/.exec(csp)?.[1];
    const body = await response.text();
    const injection =
      "<script>window.__awap_inline_executed__ = true;</script>" +
      (nonce
        ? `<script nonce="${nonce}">try { window.__awap_eval_result__ = eval("1 + 1"); } catch { window.__awap_eval_result__ = "blocked"; }</script>`
        : "");
    await route.fulfill({
      response,
      body: body.includes("</body>")
        ? body.replace("</body>", `${injection}</body>`)
        : `${body}${injection}`,
    });
  });

  await page.goto("/");
  const state = await page.evaluate(() => {
    const global = window as unknown as Record<string, unknown>;
    return {
      inlineExecuted: global.__awap_inline_executed__ === true,
      evalResult: global.__awap_eval_result__,
    };
  });
  expect(state.inlineExecuted).toBe(false);
  expect(state.evalResult).toBe("blocked");
});

test("the CSP collector accepts redacted reports", async ({ request }) => {
  const response = await request.post("/api/csp-report", {
    data: {
      "csp-report": {
        "document-uri": "http://localhost:3000/tasks?token=secret",
        "blocked-uri": "https://evil.example.com/script.js?x=1",
        "script-sample": "document.cookie",
        "violated-directive": "script-src-elem",
      },
    },
  });
  expect(response.status()).toBe(204);
});
