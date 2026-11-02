// Deterministically creates published workflow definitions and starts instances, which write
// durable outbox rows. Used by the polling-vs-CDC benchmark (issue #159).
//
// Usage:
//   node load-test/generate-outbox-events.mjs --count 200 --concurrency 10
//
// Requires load-test/seed.json (or TENANT_ID/ACCESS_TOKEN in the environment). If no
// WORKFLOW_DEFINITION_ID is supplied, a START -> END definition is created and published.
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const SEED_FILE = fileURLToPath(new URL("./seed.json", import.meta.url));

function parseArgs(argv) {
  const options = {
    count: 200,
    concurrency: 10,
    apiBase: process.env.API_BASE ?? "http://localhost:8080",
    tenantId: process.env.TENANT_ID ?? "",
    token: process.env.ACCESS_TOKEN ?? "",
    csrfToken: process.env.CSRF_TOKEN ?? "",
    workflowDefinitionId: process.env.WORKFLOW_DEFINITION_ID ?? "",
    suffix: process.env.LOAD_TEST_SUFFIX ?? `${Date.now()}-${process.pid}`,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const next = argv[index + 1];
    if (argument === "--count") {
      options.count = Number.parseInt(next, 10);
      index += 1;
    } else if (argument === "--concurrency") {
      options.concurrency = Number.parseInt(next, 10);
      index += 1;
    } else if (argument === "--api-base") {
      options.apiBase = next;
      index += 1;
    } else if (argument === "--suffix") {
      options.suffix = next;
      index += 1;
    } else if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else {
      throw new Error(`unknown argument: ${argument}`);
    }
  }

  if (!Number.isInteger(options.count) || options.count < 1) {
    throw new Error("--count must be a positive integer");
  }
  if (!Number.isInteger(options.concurrency) || options.concurrency < 1) {
    throw new Error("--concurrency must be a positive integer");
  }
  return options;
}

async function loadSeed(options) {
  if (options.tenantId && options.token) {
    return options;
  }
  try {
    const seeded = JSON.parse(await readFile(SEED_FILE, "utf8"));
    const tenant = seeded.tenants?.[0];
    if (!tenant?.tenantId || !tenant?.token) {
      throw new Error("seed.json has no tenant with tenantId/token");
    }
    return {
      ...options,
      tenantId: options.tenantId || tenant.tenantId,
      token: options.token || tenant.token,
      csrfToken: options.csrfToken || tenant.csrfToken || "",
      suffix: options.suffix || seeded.suffix,
    };
  } catch (error) {
    throw new Error(
      `TENANT_ID/ACCESS_TOKEN are not set and ${SEED_FILE} could not be used: ${error.message}`,
    );
  }
}

async function jsonRequest(base, path, init = {}) {
  const response = await fetch(`${base}${path}`, init);
  const body = await response.json().catch(() => ({}));
  return { status: response.status, body };
}

/** Bootstraps the CSRF cookie/token the same way the SPA does before a state-changing request. */
async function ensureCsrf(options) {
  if (options.csrfToken) {
    return options.csrfToken;
  }
  const response = await fetch(`${options.apiBase}/api/v1/auth/csrf`, {
    headers: {
      Cookie: `awap_access_token=${encodeURIComponent(options.token)}; awap_session=1`,
    },
  });
  const body = await response.json().catch(() => ({}));
  const cookie = response.headers
    .getSetCookie?.()
    .find((entry) => entry.startsWith("XSRF-TOKEN="));
  const csrfToken =
    body.token ||
    (cookie ? decodeURIComponent(cookie.split(";")[0].slice("XSRF-TOKEN=".length)) : "");
  if (!csrfToken) {
    throw new Error("CSRF bootstrap returned no token");
  }
  options.csrfToken = csrfToken;
  return csrfToken;
}

async function ensureWorkflowDefinition(options, headers) {
  if (options.workflowDefinitionId) {
    return options.workflowDefinitionId;
  }

  const created = await jsonRequest(options.apiBase, "/api/v1/workflow-definitions", {
    method: "POST",
    headers,
    body: JSON.stringify({
      name: `CDC benchmark ${options.suffix}`,
      description: "Created by load-test/generate-outbox-events.mjs for the outbox benchmark.",
      definitionJson: JSON.stringify({
        nodes: [
          { id: "start", type: "START", name: "Start" },
          { id: "end", type: "END", name: "End" },
        ],
        transitions: [{ from: "start", to: "end" }],
      }),
    }),
  });
  if (created.status !== 201 && created.status !== 200) {
    throw new Error(
      `creating the benchmark workflow failed with ${created.status}: ${JSON.stringify(created.body)}`,
    );
  }

  const id = created.body.id;
  const published = await jsonRequest(
    options.apiBase,
    `/api/v1/workflow-definitions/${id}/publish`,
    { method: "POST", headers, body: "{}" },
  );
  if (published.status !== 200 && published.status !== 201) {
    throw new Error(
      `publishing the benchmark workflow failed with ${published.status}: ` +
        JSON.stringify(published.body),
    );
  }
  return id;
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));
  if (parsed.help) {
    console.log(
      "usage: node load-test/generate-outbox-events.mjs [--count 200] [--concurrency 10] " +
        "[--api-base http://localhost:8080] [--suffix <run-id>]",
    );
    return;
  }
  const options = await loadSeed(parsed);
  const csrfToken = await ensureCsrf(options);
  const headers = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${options.token}`,
    Cookie:
      `awap_access_token=${encodeURIComponent(options.token)}; awap_session=1; ` +
      `XSRF-TOKEN=${encodeURIComponent(csrfToken)}`,
    "X-XSRF-TOKEN": csrfToken,
    "X-Tenant-Id": options.tenantId,
  };

  const workflowDefinitionId = await ensureWorkflowDefinition(options, headers);
  let started = 0;
  let failed = 0;
  let firstFailure = null;

  const workers = Array.from({ length: options.concurrency }, async (_, worker) => {
    for (let index = worker; index < options.count; index += options.concurrency) {
      const response = await jsonRequest(options.apiBase, "/api/v1/workflow-instances", {
        method: "POST",
        headers,
        body: JSON.stringify({
          workflowDefinitionId,
          idempotencyKey: `cdc-benchmark-${options.suffix}-${index}`,
          contextJson: "{}",
        }),
      });
      if (response.status === 200 || response.status === 201) {
        started += 1;
      } else {
        failed += 1;
        firstFailure ??= `status ${response.status}: ${JSON.stringify(response.body)}`;
      }
    }
  });
  await Promise.all(workers);

  const summary = {
    tenantId: options.tenantId,
    workflowDefinitionId,
    requested: options.count,
    started,
    failed,
    suffix: options.suffix,
    firstFailure,
  };
  console.log(JSON.stringify(summary, null, 2));
  if (failed > 0) {
    throw new Error(`${failed} workflow start(s) failed`);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
