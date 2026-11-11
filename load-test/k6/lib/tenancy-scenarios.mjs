/**
 * k6 scenario profiles and thresholds for the 1000+ tenant suite (issue #201).
 *
 * Thresholds are derived from the SLO catalogue in `load-test/lib/slo.mjs` so the nightly k6 gate
 * and the published report cannot drift apart: a budget only has to change in one place.
 */
import { checksFor, SLO_CHECKS, THROUGHPUT_CHECK } from "../../lib/slo.mjs";

/** Converts one SLO check into its k6 threshold expression. */
export function toThresholdExpression(check) {
  if (check.stat === "count") {
    return `count${check.compare === "min" ? ">=" : "=="}${check.target}`;
  }
  if (check.compare === "min") {
    return `${check.stat}>=${check.target}`;
  }
  return `${check.stat}<${check.target}`;
}

/** Every SLO becomes a k6 threshold, keyed by the metric it measures. */
export function thresholdsFromSlo(checks = SLO_CHECKS) {
  const thresholds = {};
  for (const check of checks) {
    const expressions = thresholds[check.metric] ?? [];
    expressions.push(toThresholdExpression(check));
    thresholds[check.metric] = expressions;
  }
  return thresholds;
}

export const TENANCY_THRESHOLDS = thresholdsFromSlo([...SLO_CHECKS, THROUGHPUT_CHECK]);
/** Smoke runs three VUs with think time; the throughput floor only applies to the full profile. */
export const TENANCY_SMOKE_THRESHOLDS = thresholdsFromSlo(checksFor("smoke"));

/**
 * k6 only computes the percentiles listed here. The default set stops at p(95); the SLO catalogue
 * also gates p(99), so without this the report would read "not measured" on every p99 check.
 */
export const SUMMARY_TREND_STATS = ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"];

const MAX_VUS = 1000;

/** Full nightly profile: one VU per tenant for the isolation ramp plus concurrent race traffic. */
export function tenancyFullOptions(tenantCount = MAX_VUS) {
  const vus = Math.min(Math.max(tenantCount, 2), MAX_VUS);
  return {
    scenarios: {
      tenant_isolation: {
        executor: "ramping-vus",
        startVUs: 1,
        stages: [
          { duration: "30s", target: Math.ceil(vus / 2) },
          { duration: "1m", target: vus },
          { duration: "30s", target: 0 },
        ],
        exec: "tenantIsolation",
      },
      approval_race: {
        executor: "constant-vus",
        vus: Math.max(2, Math.min(50, Math.floor(vus / 20))),
        duration: "2m",
        exec: "approvalRace",
        startTime: "20s",
      },
      idempotent_start: {
        executor: "constant-vus",
        vus: Math.max(2, Math.min(50, Math.floor(vus / 20))),
        duration: "2m",
        exec: "idempotentStart",
        startTime: "20s",
      },
    },
    thresholds: TENANCY_THRESHOLDS,
    summaryTrendStats: SUMMARY_TREND_STATS,
  };
}

/** PR/verification profile: three scenarios, a handful of VUs, under five minutes. */
export function tenancySmokeOptions() {
  return {
    scenarios: {
      tenant_isolation: {
        executor: "constant-vus",
        vus: 3,
        duration: "20s",
        exec: "tenantIsolation",
      },
      approval_race: {
        executor: "per-vu-iterations",
        vus: 2,
        iterations: 3,
        exec: "approvalRace",
        startTime: "5s",
      },
      idempotent_start: {
        executor: "per-vu-iterations",
        vus: 2,
        iterations: 3,
        exec: "idempotentStart",
        startTime: "5s",
      },
    },
    thresholds: TENANCY_SMOKE_THRESHOLDS,
    summaryTrendStats: SUMMARY_TREND_STATS,
  };
}

/** Chooses the profile from K6_SMOKE so CI reuses one script for smoke and full runs. */
export function tenancyOptionsFor(env, tenantCount = MAX_VUS) {
  return env === "1" || env === "true" ? tenancySmokeOptions() : tenancyFullOptions(tenantCount);
}
