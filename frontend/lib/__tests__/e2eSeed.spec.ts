import {
  seedPlan,
  seedTenant,
  waitForReady,
  type FetchLike,
  type RequestInitLike,
  type ResponseLike,
} from "../e2eSeed";

function stubResponse(status: number, body = ""): ResponseLike {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => body,
  };
}

describe("seedPlan", () => {
  it("builds deterministic, collision-free fixtures from the run suffix", () => {
    const plan = seedPlan("1234-1");

    expect(plan.slug).toBe("e2e-1234-1");
    expect(plan.adminEmail).toBe("admin+1234-1@e2e.awap.dev");
    expect(seedPlan("1234-1")).toEqual(plan);
    expect(seedPlan("1234-2").slug).not.toBe(plan.slug);
  });

  it("sanitizes the suffix and falls back to a local marker", () => {
    expect(seedPlan("Run 42/abc").slug).toBe("e2e-run-42-abc");
    expect(seedPlan("   ").slug).toBe("e2e-local");
  });
});

describe("waitForReady", () => {
  it("retries until the endpoint answers 2xx", async () => {
    const sleep = jest.fn(async () => {});
    const responses = [stubResponse(503), stubResponse(200)];
    const fetchImpl = jest.fn(async () => responses.shift() as ResponseLike);

    await waitForReady(fetchImpl as unknown as FetchLike, "http://api/actuator/health", {
      attempts: 3,
      delayMs: 1,
      sleep,
    });

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledTimes(1);
  });

  it("fails after the attempt budget with the last status", async () => {
    const fetchImpl = jest.fn(async () => stubResponse(500));

    await expect(
      waitForReady(fetchImpl as unknown as FetchLike, "http://api/health", {
        attempts: 2,
        delayMs: 1,
        sleep: async () => {},
      }),
    ).rejects.toThrow(/Timed out waiting for http:\/\/api\/health .*500/);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("reports connection errors in the failure message", async () => {
    const fetchImpl = jest.fn(async () => {
      throw new Error("ECONNREFUSED");
    });

    await expect(
      waitForReady(fetchImpl as unknown as FetchLike, "http://api/health", {
        attempts: 1,
        delayMs: 1,
        sleep: async () => {},
      }),
    ).rejects.toThrow(/ECONNREFUSED/);
  });
});

describe("seedTenant", () => {
  const plan = seedPlan("9");

  it("posts the registration payload and reports a created tenant", async () => {
    const fetchImpl = jest.fn(async () => stubResponse(201));

    const result = await seedTenant(fetchImpl as unknown as FetchLike, "http://api", plan);

    expect(result).toEqual({ slug: "e2e-9", created: true });
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInitLike];
    expect(url).toBe("http://api/api/v1/tenants");
    expect(JSON.parse(String(init.data))).toMatchObject({
      slug: "e2e-9",
      adminEmail: "admin+9@e2e.awap.dev",
    });
  });

  it("treats a duplicate tenant as an idempotent re-run", async () => {
    const fetchImpl = jest.fn(async () => stubResponse(409));

    await expect(
      seedTenant(fetchImpl as unknown as FetchLike, "http://api", plan),
    ).resolves.toEqual({
      slug: "e2e-9",
      created: false,
    });
  });

  it("throws with the response detail on failure", async () => {
    const fetchImpl = jest.fn(async () => stubResponse(500, "database unavailable"));

    await expect(seedTenant(fetchImpl as unknown as FetchLike, "http://api", plan)).rejects.toThrow(
      /status 500. database unavailable/,
    );
  });
});
