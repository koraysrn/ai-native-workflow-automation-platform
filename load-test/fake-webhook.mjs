// Local-only fake webhook target with configurable latency and failure rate.
// Never point the load test at an external host: this server is the allowlisted target.
//
// Usage:
//   FAILURE_RATE=0.3 FAILURE_STATUS=500 LATENCY_MS=50 node load-test/fake-webhook.mjs
//   FAILURE_RATE=0.3 FAILURE_STATUS=429 RETRY_AFTER_SECONDS=1 node load-test/fake-webhook.mjs
import http from "node:http";
import { shouldFail } from "./lib/webhook-model.mjs";

const port = Number.parseInt(process.env.PORT ?? "9099", 10);
const latencyMs = Number.parseInt(process.env.LATENCY_MS ?? "25", 10);
const failureRate = Number.parseFloat(process.env.FAILURE_RATE ?? "0.3");
const failureStatus = Number.parseInt(process.env.FAILURE_STATUS ?? "500", 10);
const retryAfterSeconds = Number.parseInt(process.env.RETRY_AFTER_SECONDS ?? "1", 10);

let counter = 0;

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ status: "UP", failureRate, failureStatus }));
    return;
  }

  if (request.method !== "POST" || request.url !== "/webhook") {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "not found" }));
    return;
  }

  let body = "";
  request.on("data", (chunk) => {
    body += chunk;
  });
  request.on("end", () => {
    counter += 1;
    const fail = shouldFail(counter, failureRate);
    const status = fail ? failureStatus : 200;
    const headers = { "Content-Type": "application/json" };
    if (status === 429) {
      headers["Retry-After"] = String(retryAfterSeconds);
    }
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
});

server.listen(port, () => {
  console.log(
    `fake webhook listening on :${port} latency=${latencyMs}ms failureRate=${failureRate} status=${failureStatus}`,
  );
});
