import assert from "node:assert/strict";
import { test } from "node:test";
import {
  backoffDelay,
  decideAction,
  parseRetryAfter,
  retryDelaySeconds,
  shouldFail,
} from "../lib/webhook-model.mjs";

test("parseRetryAfter handles seconds, HTTP dates and garbage", () => {
  assert.equal(parseRetryAfter("5"), 5);
  assert.equal(parseRetryAfter(undefined), null);
  assert.equal(parseRetryAfter(""), null);
  assert.equal(parseRetryAfter("not-a-date"), null);
  const inTwoSeconds = new Date(Date.now() + 2000).toUTCString();
  const parsed = parseRetryAfter(inTwoSeconds);
  assert.ok(parsed === 2 || parsed === 1, `expected ~2s, got ${parsed}`);
});

test("decideAction maps statuses to succeed, retry or fail", () => {
  assert.equal(decideAction(200), "succeed");
  assert.equal(decideAction(204), "succeed");
  assert.equal(decideAction(429, "1"), "retry");
  assert.equal(decideAction(500), "retry");
  assert.equal(decideAction(503), "retry");
  assert.equal(decideAction(400), "fail");
  assert.equal(decideAction(404), "fail");
});

test("backoffDelay grows exponentially and respects the cap", () => {
  assert.equal(backoffDelay(1, 100), 100);
  assert.equal(backoffDelay(2, 100), 200);
  assert.equal(backoffDelay(3, 100), 400);
  assert.equal(backoffDelay(10, 100), 5000);
  const jittered = backoffDelay(1, 100, 50);
  assert.ok(jittered >= 100 && jittered <= 150);
});

test("shouldFail is deterministic and silent when disabled", () => {
  assert.equal(shouldFail(1, 0), false);
  assert.equal(shouldFail(1, 0.3), true);
  assert.equal(shouldFail(2, 0.3), true);
  assert.equal(shouldFail(3, 0.3), false);
  assert.equal(shouldFail(6, 0.3), false);
  assert.equal(shouldFail(1, 1), false);
});

test("retryDelaySeconds respects Retry-After for 429 only", () => {
  assert.equal(retryDelaySeconds(429, "3", 1, 100), 3);
  assert.equal(retryDelaySeconds(429, undefined, 2, 100), 0.2);
  assert.equal(retryDelaySeconds(500, "3", 2, 100), 0.2);
});
