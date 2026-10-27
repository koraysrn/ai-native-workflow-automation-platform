/** Deterministic E2E fixtures and readiness polling used by the Playwright global setup. */

export interface SeedPlan {
  slug: string;
  name: string;
  adminFullName: string;
  adminEmail: string;
  adminPassword: string;
}

export interface SeedResult {
  slug: string;
  created: boolean;
}

/** Minimal response shape shared by the Playwright API response and the test stubs. */
export interface ResponseLike {
  ok: boolean;
  status: number;
  text(): Promise<string>;
}

export interface RequestInitLike {
  method?: string;
  headers?: Record<string, string>;
  /** Request body; named `data` to match the Playwright API request options. */
  data?: string;
}

export type FetchLike = (input: string, init?: RequestInitLike) => Promise<ResponseLike>;

/** Builds collision-free but reproducible fixture values from a run suffix. */
export function seedPlan(suffix: string, slugPrefix = "e2e"): SeedPlan {
  const normalized = suffix.trim().replace(/[^a-zA-Z0-9-]/g, "-").toLowerCase() || "local";
  const slug = `${slugPrefix}-${normalized}`;
  return {
    slug,
    name: `E2E Tenant ${normalized}`,
    adminFullName: "E2E Admin",
    adminEmail: `admin+${normalized}@e2e.awap.dev`,
    adminPassword: "E2e-Password-123!",
  };
}

/** Polls a URL until it answers 2xx or the attempt budget is exhausted. */
export async function waitForReady(
  fetchImpl: FetchLike,
  url: string,
  options: { attempts: number; delayMs: number; sleep: (ms: number) => Promise<void> },
): Promise<void> {
  let lastStatus = "no response";
  for (let attempt = 1; attempt <= options.attempts; attempt++) {
    try {
      const response = await fetchImpl(url);
      if (response.ok) {
        return;
      }
      lastStatus = String(response.status);
    } catch (error) {
      lastStatus = error instanceof Error ? error.message : "request failed";
    }
    if (attempt < options.attempts) {
      await options.sleep(options.delayMs);
    }
  }
  throw new Error(`Timed out waiting for ${url} after ${options.attempts} attempts (${lastStatus}).`);
}

/**
 * Registers the E2E tenant. A 409 means a previous run already seeded the same slug, which keeps
 * the suite idempotent and safe to re-run.
 */
export async function seedTenant(
  fetchImpl: FetchLike,
  apiUrl: string,
  plan: SeedPlan,
): Promise<SeedResult> {
  const response = await fetchImpl(`${apiUrl}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    data: JSON.stringify({
      slug: plan.slug,
      name: plan.name,
      adminEmail: plan.adminEmail,
      adminPassword: plan.adminPassword,
      adminFullName: plan.adminFullName,
    }),
  });
  if (response.status === 201) {
    return { slug: plan.slug, created: true };
  }
  if (response.status === 409) {
    return { slug: plan.slug, created: false };
  }
  const detail = await response.text().catch(() => "");
  throw new Error(
    `Seeding tenant ${plan.slug} failed with status ${response.status}. ${detail}`.trim(),
  );
}
