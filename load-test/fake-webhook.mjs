// Local-only fake webhook target with a startup-configured latency and a runtime-controllable
// failure rate/status. Never point the load test at an external host: this server is the
// allowlisted target.
//
// Usage:
//   FAILURE_RATE=0.3 FAILURE_STATUS=500 LATENCY_MS=50 node load-test/fake-webhook.mjs
//   FAILURE_RATE=0.3 FAILURE_STATUS=429 RETRY_AFTER_SECONDS=1 node load-test/fake-webhook.mjs
//
// The E2E combined scenario (issue #212) uses the control endpoints to switch the behaviour per
// test and to assert the exact requests the backend delivered:
//   GET  /requests        -> every received /webhook request (method, headers, body, status)
//   POST /control         -> {"failureRate":0,"failureStatus":500,"forceFailure":true}
//   POST /reset           -> clear the request log
//
// `failureRate` is the deterministic success rate of the shared load-test model (1 = always
// succeed, 0.3 = every third request succeeds). `forceFailure` is the explicit "always fail"
// switch used by the retry/DLQ E2E variant.
//
// Latency is intentionally fixed at startup: exposing it through /control would let a caller choose
// an arbitrary timer duration on this local server.
import http from "node:http";
import { shouldFail } from "./lib/webhook-model.mjs";

const port = Number.parseInt(process.env.PORT ?? "9099", 10);
const latencyMs = Number.parseInt(process.env.LATENCY_MS ?? "25", 10);
let failureRate = Number.parseFloat(process.env.FAILURE_RATE ?? "0.3");
let failureStatus = Number.parseInt(process.env.FAILURE_STATUS ?? "500", 10);
let forceFailure = false;
const retryAfterSeconds = Number.parseInt(process.env.RETRY_AFTER_SECONDS ?? "1", 10);

let counter = 0;
const requests = [];

function json(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
}

function readBody(request) {
  return new Promise((resolve) => {
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => resolve(body));
  });
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    json(response, 200, { status: "UP", failureRate, failureStatus, forceFailure, latencyMs });
    return;
  }

  if (request.method === "GET" && request.url === "/requests") {
    json(response, 200, requests);
    return;
  }

  if (request.method === "POST" && request.url === "/reset") {
    requests.length = 0;
    counter = 0;
    json(response, 200, { reset: true });
    return;
  }

  if (request.method === "POST" && request.url === "/control") {
    try {
      const overrides = JSON.parse((await readBody(request)) || "{}");
      if (Number.isFinite(overrides.failureRate)) {
        failureRate = Math.min(1, Math.max(0, Number(overrides.failureRate)));
      }
      if (Number.isFinite(overrides.failureStatus)) {
        failureStatus = Number(overrides.failureStatus);
      }
      if (typeof overrides.forceFailure === "boolean") {
        forceFailure = overrides.forceFailure;
      }
      json(response, 200, { failureRate, failureStatus, forceFailure, latencyMs });
    } catch {
      json(response, 400, { error: "invalid control payload" });
    }
    return;
  }

  if (request.method !== "POST" || request.url !== "/webhook") {
    json(response, 404, { error: "not found" });
    return;
  }

  const body = await readBody(request);
  counter += 1;
  const fail = forceFailure || shouldFail(counter, failureRate);
  const status = fail ? failureStatus : 200;
  const headers = { "Content-Type": "application/json" };
  if (status === 429) {
    headers["Retry-After"] = String(retryAfterSeconds);
  }
  const record = {
    method: request.method,
    url: request.url,
    headers: request.headers,
    body,
    status,
    request: counter,
    receivedAt: new Date().toISOString(),
  };
  requests.push(record);
  setTimeout(() => {
    response.writeHead(status, headers);
    response.end(
      JSON.stringify({
        accepted: !fail,
        request: counter,
        receivedBytes: body.length,
      }),
    );
  }, latencyMs);
});

server.listen(port, () => {
  console.log(
    `fake webhook listening on :${port} latency=${latencyMs}ms failureRate=${failureRate} status=${failureStatus}`,
  );
});
