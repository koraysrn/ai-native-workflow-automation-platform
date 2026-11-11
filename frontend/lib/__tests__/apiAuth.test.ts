/**
 * Single-flight refresh and error classification for the API client (issue #202).
 *
 * `api.ts` keeps the in-flight refresh promise at module scope, so every test loads a fresh module
 * instance and installs its own `fetch` fake.
 */

interface FakeResponse {
  status: number;
  ok: boolean;
  json: () => Promise<unknown>;
  headers: { getSetCookie: () => string[] };
}

function fakeResponse(status: number, body: unknown = {}): FakeResponse {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
    headers: { getSetCookie: () => [] },
  };
}

async function loadApi() {
  jest.resetModules();
  return import("../api");
}

function installFetch(handler: (url: string) => FakeResponse | Promise<FakeResponse>): jest.Mock {
  const mock = jest.fn(async (input: RequestInfo | URL) => handler(String(input)));
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  document.cookie = "awap_tenant=tenant-1";
});

afterEach(() => {
  jest.useRealTimers();
});

describe("single-flight refresh", () => {
  it("issues one refresh for concurrent 401s and retries each request once", async () => {
    const api = await loadApi();
    let refreshCalls = 0;
    let taskCalls = 0;
    let instanceCalls = 0;
    const fetchMock = installFetch((url) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        refreshCalls += 1;
        return fakeResponse(200, {});
      }
      if (url.endsWith("/api/v1/tasks")) {
        taskCalls += 1;
        return taskCalls === 1
          ? fakeResponse(401, {})
          : fakeResponse(200, [{ id: "task-1", title: "Review", status: "OPEN" }]);
      }
      if (url.endsWith("/api/v1/workflow-instances")) {
        instanceCalls += 1;
        return instanceCalls === 1
          ? fakeResponse(401, {})
          : fakeResponse(200, [{ id: "instance-1", state: "RUNNING" }]);
      }
      throw new Error(`unexpected url ${url}`);
    });

    const [tasks, instances] = await Promise.all([api.listTasks(), api.listWorkflowInstances()]);

    expect(refreshCalls).toBe(1);
    expect(taskCalls).toBe(2);
    expect(instanceCalls).toBe(2);
    expect(tasks).toHaveLength(1);
    expect(instances).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalled();
  });

  it("refreshes again after the shared promise settled", async () => {
    const api = await loadApi();
    let refreshCalls = 0;
    let taskCalls = 0;
    installFetch((url) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        refreshCalls += 1;
        return fakeResponse(200, {});
      }
      taskCalls += 1;
      // Calls 1 and 3 are 401s (two separate expiry events), calls 2 and 4 retries.
      return taskCalls % 2 === 1 ? fakeResponse(401, {}) : fakeResponse(200, []);
    });

    await expect(api.listTasks()).resolves.toEqual([]);
    await expect(api.listTasks()).resolves.toEqual([]);
    expect(refreshCalls).toBe(2);
    expect(taskCalls).toBe(4);
  });

  it("redirects to the login page when the refresh fails", async () => {
    const api = await loadApi();
    installFetch((url) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        return fakeResponse(401, {});
      }
      return fakeResponse(401, {});
    });
    const redirect = jest
      .spyOn(api.navigation, "redirectToLogin")
      .mockImplementation(() => undefined);

    await expect(api.listTasks()).rejects.toMatchObject({ status: 401 });
    expect(redirect).toHaveBeenCalledTimes(1);
    redirect.mockRestore();
  });
});

describe("error classification", () => {
  it("throws a typed 403 without trying to refresh", async () => {
    const api = await loadApi();
    let refreshCalls = 0;
    installFetch((url) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        refreshCalls += 1;
      }
      return fakeResponse(403, { detail: "Forbidden for this role." });
    });

    await expect(api.listTasks()).rejects.toMatchObject({
      name: "ApiError",
      status: 403,
      message: "Forbidden for this role.",
    });
    expect(refreshCalls).toBe(0);
    expect(api.errorStatus(new api.ApiError(403, "x"))).toBe(403);
  });

  it("keeps the 5xx status and RFC 7807 detail", async () => {
    const api = await loadApi();
    installFetch(() => fakeResponse(503, { title: "Service unavailable" }));
    await expect(api.listWorkflowInstances()).rejects.toMatchObject({
      status: 503,
      message: "Service unavailable",
    });
    expect(api.errorStatus(new Error("plain"))).toBeUndefined();
  });

  it("returns undefined for a 204 response", async () => {
    const api = await loadApi();
    installFetch(() => fakeResponse(204));
    await expect(api.markNotificationRead("notification-1")).resolves.toBeUndefined();
  });
});

describe("request headers", () => {
  it("sends the CSRF header and tenant id on mutations", async () => {
    const api = await loadApi();
    const fetchMock = installFetch(() => fakeResponse(200, { id: "task-1" }));

    await api.approveTask("task-1", "looks good");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/tasks/task-1/approve");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      "X-XSRF-TOKEN": "csrf-token",
      "X-Tenant-Id": "tenant-1",
      "Content-Type": "application/json",
    });
    expect(init.body).toBe(JSON.stringify({ comment: "looks good" }));
    expect(init.credentials).toBe("include");
  });

  it("bootstraps the CSRF cookie when it is missing", async () => {
    const api = await loadApi();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    const seen: string[] = [];
    installFetch((url) => {
      seen.push(url);
      if (url.endsWith("/api/v1/auth/csrf")) {
        document.cookie = "XSRF-TOKEN=bootstrapped";
        return fakeResponse(200, {});
      }
      return fakeResponse(200, {});
    });

    await api.approveTask("task-1");

    expect(seen[0]).toContain("/api/v1/auth/csrf");
    expect(seen[1]).toContain("/api/v1/tasks/task-1/approve");
  });
});
