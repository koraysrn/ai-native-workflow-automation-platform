// Seeds 1000+ tenants for the tenancy load test (issue #201).
//
// Usage:
//   node load-test/seed-tenants.mjs --tenants 1000 --concurrency 16
//   node load-test/seed-tenants.mjs --dry-run        # validate the plan, no API calls
//   node load-test/seed-tenants.mjs --tenants 20 --suffix local-verify
//
// The credential file (default load-test/tenants.json) is read by
// load-test/k6/awap-tenancy-load-test.js through TENANTS_FILE.
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import {
  createHttpClient,
  parseSeedArgs,
  refreshTenants,
  seedTenants,
  UsageError,
  USAGE,
} from "./lib/seed-tenants.mjs";
import { chunk, planTenants } from "./lib/tenancy.mjs";

/** Testable entry point: dependencies are injected so `node --test` never opens a socket. */
export async function main(argv = process.argv.slice(2), deps = {}) {
  const log = deps.log ?? ((line) => console.log(line));
  const write = deps.writeFile ?? writeFile;
  const read = deps.readFile ?? readFile;

  let options;
  try {
    options = parseSeedArgs(argv);
  } catch (error) {
    if (error instanceof UsageError) {
      log(`error: ${error.message}\n\n${USAGE}`);
      return 2;
    }
    throw error;
  }

  if (options.help) {
    log(USAGE);
    return 0;
  }

  const suffix = options.suffix ?? `${Date.now()}-${process.pid}`;
  const fixtures = planTenants({
    suffix,
    count: options.count,
    startIndex: options.startIndex,
  });

  if (options.dryRun) {
    log(
      JSON.stringify(
        {
          mode: "dry-run",
          apiBase: options.apiBase,
          tenants: fixtures.length,
          concurrency: options.concurrency,
          batches: chunk(fixtures, options.concurrency).length,
          firstSlug: fixtures[0].slug,
          lastSlug: fixtures[fixtures.length - 1].slug,
          output: options.output,
        },
        null,
        2,
      ),
    );
    return 0;
  }

  const client = deps.createClient
    ? deps.createClient(options)
    : createHttpClient({
        base: options.apiBase,
        retries: options.retries,
        timeoutMs: options.timeoutMs,
      });

  // Refresh mode re-authenticates the tenants already in the credential file. A 1000-tenant seed
  // outlives the 15-minute access token, so the load run must not reuse the seed-time tokens.
  if (options.refresh) {
    let stored;
    try {
      stored = JSON.parse(await read(options.output, "utf8"));
    } catch (error) {
      log(`error: cannot read the credential file ${options.output}: ${error.message}`);
      return 2;
    }
    if (!Array.isArray(stored?.tenants) || stored.tenants.length === 0) {
      log(`error: ${options.output} contains no tenants to refresh`);
      return 2;
    }
    const refreshed = await refreshTenants({
      client,
      tenants: stored.tenants,
      concurrency: options.concurrency,
      log,
    });
    if (refreshed.tenants.length > 0) {
      await write(
        options.output,
        JSON.stringify(
          { ...stored, refreshedAt: new Date().toISOString(), tenants: refreshed.tenants },
          null,
          2,
        ),
      );
    }
    log(
      `Refreshed ${refreshed.tenants.length}/${stored.tenants.length} credential(s)` +
        (refreshed.failures.length > 0 ? `; ${refreshed.failures.length} failure(s)` : ""),
    );
    for (const failure of refreshed.failures) {
      log(`failed ${failure.slug}: ${failure.message}`);
    }
    return refreshed.failures.length > 0 || refreshed.unprocessed > 0 ? 1 : 0;
  }

  const summary = await seedTenants({
    client,
    fixtures,
    concurrency: options.concurrency,
    log,
  });

  if (summary.seeded.length > 0) {
    await write(
      options.output,
      JSON.stringify(
        {
          generatedAt: new Date().toISOString(),
          suffix,
          apiBase: options.apiBase,
          count: summary.seeded.length,
          tenants: summary.seeded,
        },
        null,
        2,
      ),
    );
  }

  log(
    `Seeded ${summary.seeded.length}/${fixtures.length} tenant(s) with suffix ${suffix}` +
      (summary.unprocessed > 0 ? ` (${summary.unprocessed} not attempted)` : "") +
      (summary.failures.length > 0 ? `; ${summary.failures.length} failure(s)` : ""),
  );
  for (const failure of summary.failures) {
    log(`failed ${failure.slug}: ${failure.message}`);
  }

  return summary.failures.length > 0 || summary.seeded.length === 0 ? 1 : 0;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error);
      process.exitCode = 1;
    });
}
