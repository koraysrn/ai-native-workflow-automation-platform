// Runs ESLint with typescript-eslint using the TypeScript 6 API, so that the
// project can keep TypeScript 7 for typecheck/build until typescript-eslint
// ships TS 7 support (typescript-eslint#10940).
// See https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/#running-side-by-side-with-typescript-6.0
import Module from "node:module";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const typescript6Entry = require.resolve("typescript-6");

const originalResolveFilename = Module._resolveFilename;
Module._resolveFilename = function (request, parent, isMain, options) {
  if (request === "typescript") {
    return typescript6Entry;
  }
  return originalResolveFilename.call(this, request, parent, isMain, options);
};

const { ESLint } = await import("eslint");

const eslint = new ESLint();
const results = await eslint.lintFiles(["."]);
const formatter = await eslint.loadFormatter("stylish");
const output = await formatter.format(results);
if (output) {
  process.stdout.write(output);
}

const errorCount = results.reduce((sum, result) => sum + result.errorCount, 0);
if (errorCount > 0) {
  process.exitCode = 1;
}
