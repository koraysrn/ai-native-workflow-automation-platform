import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const POM = new URL("../../backend/pom.xml", import.meta.url);
const LEGACY_PROPERTIES = new URL("../../backend/sonar-project.properties", import.meta.url);
const CI_WORKFLOW = new URL("../../.github/workflows/ci.yml", import.meta.url);
const DOCS = new URL("../../docs/sonarqube.md", import.meta.url);

function pomProperty(pom, name) {
  const pattern = new RegExp(`<${name.replaceAll(".", "\\.")}>(.*?)</${name.replaceAll(".", "\\.")}>`);
  return pattern.exec(pom)?.[1]?.trim();
}

test("the pom declares the SonarQube project key and organization", async () => {
  const pom = await readFile(POM, "utf8");
  const projectKey = pomProperty(pom, "sonar.projectKey");
  const organization = pomProperty(pom, "sonar.organization");
  assert.ok(projectKey, "sonar.projectKey must be declared in backend/pom.xml");
  assert.ok(
    organization,
    "sonar.organization is mandatory on SonarQube Cloud",
  );
  assert.equal(
    pomProperty(pom, "sonar.qualitygate.wait"),
    "true",
    "the quality gate must stay blocking",
  );
  assert.equal(
    pomProperty(pom, "sonar.coverage.jacoco.xmlReportPaths"),
    "common/target/site/jacoco/jacoco.xml,identity/target/site/jacoco/jacoco.xml",
  );
});

test("the project key is not the old placeholder default", async () => {
  const pom = await readFile(POM, "utf8");
  assert.notEqual(
    pomProperty(pom, "sonar.projectKey"),
    "awap",
    "the key must match the project bound to the repository",
  );
});

test("the Maven scanner keeps inferring the analysis scope per module", async () => {
  const pom = await readFile(POM, "utf8");
  assert.equal(
    pomProperty(pom, "sonar.sources"),
    undefined,
    "root-relative sonar.sources would be applied to every module",
  );
  assert.equal(
    pomProperty(pom, "sonar.tests"),
    undefined,
    "root-relative sonar.tests would be applied to every module",
  );
});

test("no sonar-project.properties file is committed for the Maven scanner", () => {
  assert.equal(
    existsSync(fileURLToPath(LEGACY_PROPERTIES)),
    false,
    "the SonarScanner for Maven ignores sonar-project.properties; keep the pom as the only source",
  );
});

test("CI reads the project key from the pom instead of hardcoding it", async () => {
  const workflow = await readFile(CI_WORKFLOW, "utf8");
  assert.doesNotMatch(workflow, /SONAR_PROJECT_KEY: awap/);
  assert.match(workflow, /<sonar\\\.projectKey>/);
  assert.match(workflow, /sed -n 's:\.\*<sonar/);
  assert.match(workflow, /echo "SONAR_PROJECT_KEY=\$project_key" >> "\$GITHUB_ENV"/);
});

test("the SonarQube runbook documents the organization requirement and Automatic Analysis", async () => {
  const docs = await readFile(DOCS, "utf8");
  assert.match(docs, /sonar\.organization/);
  assert.match(docs, /Automatic Analysis/);
  assert.match(docs, /sonar-project\.properties/);
  assert.doesNotMatch(docs, /-Dsonar\.projectKey=awap/);
});
