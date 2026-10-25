/** @jest-environment node */

import path from "node:path";
import { createHmac } from "node:crypto";
import { MatchersV3, PactV3 } from "@pact-foundation/pact";
import { healthCheck, login, registerTenant } from "../api";

const M = MatchersV3;

/** IDs shared with the provider-state seeding in IdentityProviderContractIT. */
const TENANT_ID = "00000000-0000-0000-0000-000000000001";
const USER_ID = "00000000-0000-0000-0000-000000000002";

/** Test-only JWT secret; must match awap.security.jwt.secret in the test resources. */
const TEST_JWT_SECRET = "test-secret-0123456789abcdef0123456789abcdef";

/** Jackson serializes Instant as ISO-8601 with optional fractional seconds and a Z suffix. */
const ISO_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

const provider = new PactV3({
  consumer: "awap-frontend",
  provider: "awap-identity",
  dir: path.resolve(process.cwd(), "pacts"),
});

/** Issues a Bearer token for the seeded tenant admin, mirroring JwtService on the provider. */
function signJwt(): string {
  const header = { alg: "HS256", typ: "JWT" };
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    sub: USER_ID,
    tenantId: TENANT_ID,
    email: "ada@acme.com",
    role: "TENANT_ADMIN",
    iat: now,
    // Long expiry so the fixed pact token stays valid between consumer generation
    // (CI frontend job) and provider verification (CI backend job).
    exp: now + 365 * 24 * 60 * 60,
  };
  const encodedHeader = Buffer.from(JSON.stringify(header)).toString("base64url");
  const encodedPayload = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const signature = createHmac("sha256", TEST_JWT_SECRET)
    .update(`${encodedHeader}.${encodedPayload}`)
    .digest("base64url");
  return `${encodedHeader}.${encodedPayload}.${signature}`;
}

/** Runs an authenticated GET against the mock server and returns the parsed JSON body. */
async function authedGet(mockServer: { url: string }, token: string, path: string): Promise<unknown> {
  const response = await fetch(`${mockServer.url}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.status).toBe(200);
  return await response.json();
}

describe("awap-identity health contract", () => {
  it("verifies the health endpoint response", () => {
    provider
      .uponReceiving("a health check request")
      .withRequest({ method: "GET", path: "/actuator/health" })
      .willRespondWith({
        status: 200,
        headers: {
          "Content-Type": "application/vnd.spring-boot.actuator.v3+json",
        },
        body: { status: M.string("UP") },
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const result = await healthCheck();
      expect(result.status).toBe("UP");
    });
  });
});

describe("awap-identity tenant registration contract", () => {
  it("verifies every field of the created tenant response", () => {
    provider
      .uponReceiving("a tenant registration request")
      .withRequest({
        method: "POST",
        path: "/api/v1/tenants",
        headers: { "Content-Type": "application/json" },
        body: {
          slug: "acme-corp",
          name: "Acme Corporation",
          adminEmail: "admin@acme-corp.com",
          adminPassword: "s3cret-pass",
          adminFullName: "Ada Lovelace",
        },
      })
      .willRespondWith({
        status: 201,
        headers: { "Content-Type": "application/json" },
        body: {
          id: M.uuid("00000000-0000-0000-0000-000000000000"),
          slug: M.string("acme-corp"),
          name: M.string("Acme Corporation"),
          plan: M.string("FREE"),
          maxUsers: M.integer(100),
          maxWorkflows: M.integer(50),
          maxAiCallsPerMonth: M.integer(1000),
          active: M.boolean(true),
        },
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const tenant = await registerTenant({
        slug: "acme-corp",
        name: "Acme Corporation",
        adminEmail: "admin@acme-corp.com",
        adminPassword: "s3cret-pass",
        adminFullName: "Ada Lovelace",
      });
      expect(tenant.id).toBeDefined();
      expect(tenant.slug).toBe("acme-corp");
      expect(tenant.name).toBe("Acme Corporation");
      expect(tenant.plan).toBe("FREE");
      expect(tenant.maxUsers).toBe(100);
      expect(tenant.maxWorkflows).toBe(50);
      expect(tenant.maxAiCallsPerMonth).toBe(1000);
      expect(tenant.active).toBe(true);
    });
  });
});

describe("awap-identity login contract", () => {
  it("verifies the token response shape", () => {
    provider
      .given("a tenant and an admin user exist")
      .uponReceiving("a login request")
      .withRequest({
        method: "POST",
        path: "/api/v1/auth/login",
        headers: { "Content-Type": "application/json" },
        body: { tenantSlug: "acme", email: "ada@acme.com", password: "s3cret" },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: {
          accessToken: null,
          refreshToken: null,
          expiresIn: M.integer(900),
        },
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const token = await login("acme", "ada@acme.com", "s3cret");
      expect(token.accessToken).toBeNull();
      expect(token.refreshToken).toBeNull();
      expect(token.expiresIn).toBe(900);
    });
  });
});

describe("awap-identity current user contract", () => {
  it("verifies every field of the current user response", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a current user request")
      .withRequest({
        method: "GET",
        path: "/api/v1/auth/me",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: {
          id: M.uuid(USER_ID),
          tenantId: M.uuid(TENANT_ID),
          email: M.string("ada@acme.com"),
          fullName: M.string("Ada Lovelace"),
          role: M.string("TENANT_ADMIN"),
        },
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const user = (await authedGet(mockServer, token, "/api/v1/auth/me")) as Record<string, unknown>;
      expect(user.id).toBeDefined();
      expect(user.tenantId).toBeDefined();
      expect(user.email).toBe("ada@acme.com");
      expect(user.fullName).toBe("Ada Lovelace");
      expect(user.role).toBe("TENANT_ADMIN");
    });
  });
});

describe("awap-identity tenant membership contract", () => {
  it("verifies the tenant membership response shape", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a tenant membership request")
      .withRequest({
        method: "GET",
        path: "/api/v1/auth/tenants",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: M.eachLike({
          tenantId: M.uuid(TENANT_ID),
          role: M.string("TENANT_ADMIN"),
        }),
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const memberships = (await authedGet(
        mockServer,
        token,
        "/api/v1/auth/tenants",
      )) as Array<Record<string, unknown>>;
      expect(memberships.length).toBeGreaterThan(0);
      const first = memberships[0]!;
      expect(first.tenantId).toBeDefined();
      expect(first.role).toBe("TENANT_ADMIN");
    });
  });
});

describe("awap-identity task contract", () => {
  it("verifies every field of the task list response", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a task list request")
      .withRequest({
        method: "GET",
        path: "/api/v1/tasks",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: M.eachLike({
          id: M.uuid("00000000-0000-0000-0000-000000000010"),
          tenantId: M.uuid(TENANT_ID),
          workflowInstanceId: null,
          title: M.string("Review contract"),
          description: null,
          assigneeId: null,
          status: M.string("OPEN"),
          version: M.integer(0),
          decisionComment: null,
        }),
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const tasks = (await authedGet(mockServer, token, "/api/v1/tasks")) as Array<
        Record<string, unknown>
      >;
      expect(tasks.length).toBeGreaterThan(0);
      const first = tasks[0]!;
      expect(first.id).toBeDefined();
      expect(first.tenantId).toBeDefined();
      expect(first).toHaveProperty("workflowInstanceId");
      expect(first.title).toBe("Review contract");
      expect(first).toHaveProperty("description");
      expect(first).toHaveProperty("assigneeId");
      expect(first.status).toBe("OPEN");
      expect(first.version).toBe(0);
      expect(first).toHaveProperty("decisionComment");
    });
  });
});

describe("awap-identity form contract", () => {
  it("verifies every field of the form definition list response", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a form definition list request")
      .withRequest({
        method: "GET",
        path: "/api/v1/forms",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: M.eachLike({
          id: M.uuid("00000000-0000-0000-0000-000000000040"),
          tenantId: M.uuid(TENANT_ID),
          name: M.string("Onboarding form"),
          schemaJson: M.string('{"fields":[]}'),
          version: M.integer(1),
          createdAt: M.regex(ISO_TIMESTAMP, "2026-01-01T00:00:00.000Z"),
        }),
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const forms = (await authedGet(mockServer, token, "/api/v1/forms")) as Array<
        Record<string, unknown>
      >;
      expect(forms.length).toBeGreaterThan(0);
      const first = forms[0]!;
      expect(first.id).toBeDefined();
      expect(first.tenantId).toBeDefined();
      expect(first.name).toBe("Onboarding form");
      expect(first.schemaJson).toBeDefined();
      expect(first.version).toBe(1);
      expect(first.createdAt).toBeDefined();
    });
  });
});

describe("awap-identity workflow definition contract", () => {
  it("verifies every field of the workflow definition list response", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a workflow definition list request")
      .withRequest({
        method: "GET",
        path: "/api/v1/workflow-definitions",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: M.eachLike({
          id: M.uuid("00000000-0000-0000-0000-000000000020"),
          tenantId: M.uuid(TENANT_ID),
          name: M.string("Approval flow"),
          description: null,
          status: M.string("DRAFT"),
          currentVersion: M.integer(0),
        }),
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const definitions = (await authedGet(
        mockServer,
        token,
        "/api/v1/workflow-definitions",
      )) as Array<Record<string, unknown>>;
      expect(definitions.length).toBeGreaterThan(0);
      const first = definitions[0]!;
      expect(first.id).toBeDefined();
      expect(first.tenantId).toBeDefined();
      expect(first.name).toBe("Approval flow");
      expect(first).toHaveProperty("description");
      expect(first.status).toBe("DRAFT");
      expect(first.currentVersion).toBe(0);
    });
  });
});

describe("awap-identity workflow instance contract", () => {
  it("verifies every field of the workflow instance list response", () => {
    const token = signJwt();
    provider
      .given("an authenticated tenant admin exists")
      .uponReceiving("a workflow instance list request")
      .withRequest({
        method: "GET",
        path: "/api/v1/workflow-instances",
        headers: { Authorization: `Bearer ${token}` },
      })
      .willRespondWith({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: M.eachLike({
          id: M.uuid("00000000-0000-0000-0000-000000000030"),
          tenantId: M.uuid(TENANT_ID),
          workflowDefinitionId: M.uuid("00000000-0000-0000-0000-000000000020"),
          workflowVersion: M.integer(1),
          state: M.string("RUNNING"),
          contextJson: M.string("{}"),
          idempotencyKey: null,
          createdAt: M.regex(ISO_TIMESTAMP, "2026-01-01T00:00:00.000Z"),
        }),
      });

    return provider.executeTest(async (mockServer) => {
      process.env.NEXT_PUBLIC_API_BASE = mockServer.url;
      const instances = (await authedGet(
        mockServer,
        token,
        "/api/v1/workflow-instances",
      )) as Array<Record<string, unknown>>;
      expect(instances.length).toBeGreaterThan(0);
      const first = instances[0]!;
      expect(first.id).toBeDefined();
      expect(first.tenantId).toBeDefined();
      expect(first.workflowDefinitionId).toBeDefined();
      expect(first.workflowVersion).toBe(1);
      expect(first.state).toBe("RUNNING");
      expect(first.contextJson).toBeDefined();
      expect(first).toHaveProperty("idempotencyKey");
      expect(first.createdAt).toBeDefined();
    });
  });
});
