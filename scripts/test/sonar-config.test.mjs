import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const PROPERTIES = new URL("../../backend/sonar-project.properties", import.meta.url);
const CI_WORKFLOW = new URL("../../.github/workflows/ci.yml", import.meta.url);
const DOCS = new URL("../../docs/sonarqube.md", import.meta.url);

async function sonarProperties() {
  const raw = await readFile(PROPERTIES, "utf8");
  const entries = new Map();
  for (const line of raw.split("\n")) {
    if (!line || line.startsWith("#") || !line.includes("=")) {
      continue;
    }
    const index = line.indexOf("=");
    entries.set(line.slice(0, index).trim(), line.slice(index + 1).trim());
  }
  return entries;
}

test("sonar-project.properties declares the project key and the organization", async () => {
  const properties = await sonarProperties();
  assert.ok(properties.get("sonar.projectKey"), "sonar.projectKey must be declared");
  assert.ok(
    properties.get("sonar.organization"),
    "sonar.organization is mandatory on SonarQube Cloud",
  );
  assert.equal(
    properties.get("sonar.qualitygate.wait"),
    "true",
    "the quality gate must stay blocking",
  );
});

test("the project key is not the old placeholder default", async () => {
  const properties = await sonarProperties();
  assert.notEqual(
    properties.get("sonar.projectKey"),
    "awap",
    "the key must match the project bound to the repository",
  );
});

test("CI reads the project key from sonar-project.properties instead of hardcoding it", async () => {
  const workflow = await readFile(CI_WORKFLOW, "utf8");
  assert.doesNotMatch(workflow, /SONAR_PROJECT_KEY: awap/);
  assert.match(workflow, /sed -n 's\/\^sonar\\\.projectKey=\/\/p' backend\/sonar-project\.properties/);
  assert.match(workflow, /echo "SONAR_PROJECT_KEY=\$project_key" >> "\$GITHUB_ENV"/);
});

test("the SonarQube runbook documents the organization requirement and Automatic Analysis", async () => {
  const docs = await readFile(DOCS, "utf8");
  assert.match(docs, /sonar\.organization/);
  assert.match(docs, /Automatic Analysis/);
  assert.doesNotMatch(docs, /-Dsonar\.projectKey=awap/);
});
