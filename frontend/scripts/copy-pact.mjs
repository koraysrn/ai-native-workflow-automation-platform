import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, "..", "pacts", "awap-frontend-awap-identity.json");
const target = resolve(
  here,
  "..",
  "..",
  "backend",
  "identity",
  "src",
  "test",
  "resources",
  "pacts",
  "awap-frontend-awap-identity.json",
);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`Pact copied to ${target}`);
