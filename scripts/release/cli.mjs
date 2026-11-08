import { appendFileSync } from "node:fs";

/**
 * Error raised by the shared argument parser. The `pathId` is one of the trace
 * ids declared by the calling script, so the node:test coverage gate can prove
 * that every parser error path is exercised.
 */
export class UsageError extends Error {
  constructor(pathId, message) {
    super(message);
    this.name = "UsageError";
    this.pathId = pathId;
  }
}

/**
 * Parse `--key value` / `--key=value` / `--flag` arguments.
 *
 * Empty values are preserved on purpose: workflow_dispatch passes optional
 * inputs as `--from ""`, and the caller decides whether an empty value means
 * "use the default".
 */
export function parseArgs(argv, { flags = [], values = [] } = {}) {
  const flagSet = new Set(flags);
  const valueSet = new Set(values);
  const options = {};

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
      continue;
    }
    if (!argument.startsWith("--")) {
      throw new UsageError("error_unexpected_argument", `unexpected argument '${argument}'`);
    }

    const body = argument.slice(2);
    const equals = body.indexOf("=");
    const name = equals === -1 ? body : body.slice(0, equals);
    const inline = equals === -1 ? undefined : body.slice(equals + 1);

    if (flagSet.has(name)) {
      if (inline !== undefined) {
        throw new UsageError("error_unexpected_argument", `--${name} does not take a value`);
      }
      options[name] = true;
      continue;
    }

    if (!valueSet.has(name)) {
      throw new UsageError("error_unknown_option", `unknown option '--${name}'`);
    }

    const value = inline !== undefined ? inline : argv[index + 1];
    if (value === undefined) {
      throw new UsageError("error_missing_value", `--${name} requires a value`);
    }
    if (inline === undefined) {
      index += 1;
    }
    options[name] = value;
  }

  return options;
}

/**
 * Create a trace recorder for the branch-coverage gate. Writing to the trace
 * file is opt-in so the scripts stay silent during normal local use.
 */
export function traceRecorder(pathIds, variable) {
  return (pathId) => {
    if (!pathIds.includes(pathId)) {
      throw new Error(`internal error: unknown trace path '${pathId}'`);
    }
    const file = process.env[variable];
    if (file) {
      appendFileSync(file, `${pathId}\n`);
    }
  };
}

/** Append `key=value` lines to a GitHub Actions output file. */
export function writeGithubOutput(file, entries) {
  if (!file) {
    return;
  }
  const lines = Object.entries(entries).map(([key, value]) => `${key}=${value}`);
  appendFileSync(file, `${lines.join("\n")}\n`);
}

/** Emit a GitHub Actions error annotation. */
export function fail(message) {
  console.error(`::error::${message}`);
}

/** Emit a GitHub Actions warning annotation. */
export function warn(message) {
  console.error(`::warning::${message}`);
}

/** Print the trace ids one per line for the coverage gate. */
export function listPaths(pathIds) {
  console.log(pathIds.join("\n"));
}
