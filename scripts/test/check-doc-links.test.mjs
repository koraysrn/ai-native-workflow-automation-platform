import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  checkLinks,
  extractLocalLinks,
  markdownAnchors,
  slugifyHeading,
  validateLink,
} from "../check-doc-links.mjs";

function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), "awap-links-"));
  for (const [relativePath, contents] of Object.entries(files)) {
    const full = join(root, relativePath);
    mkdirSync(join(full, ".."), { recursive: true });
    writeFileSync(full, contents, "utf8");
  }
  return root;
}

test("extractLocalLinks ignores external, mailto and code-fence links", () => {
  const links = extractLocalLinks(
    [
      "[docs](docs/readme.md)",
      "[web](https://example.com)",
      "[mail](mailto:a@b.c)",
      "```",
      "[ignored](missing.md)",
      "```",
      "[anchor](#section)",
    ].join("\n"),
  );
  assert.deepEqual(links, ["docs/readme.md", "#section"]);
});

test("slugifyHeading and markdownAnchors follow GitHub slug rules", () => {
  assert.equal(slugifyHeading("AI layer capabilities"), "ai-layer-capabilities");
  const anchors = markdownAnchors("## Hello World\n### Nested `code`\n");
  assert.ok(anchors.has("hello-world"));
  assert.ok(anchors.has("nested-code"));
});

test("validateLink accepts existing files and anchors", () => {
  const root = fixture({
    "a.md": "# A\n## Target section\n",
    "b.md": "[a](a.md#target-section)",
  });
  try {
    assert.equal(validateLink(join(root, "b.md"), "a.md#target-section", root), undefined);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("checkLinks reports missing files and anchors", () => {
  const root = fixture({
    "a.md": "# A\n",
    "b.md": "[missing](missing.md)\n[bad anchor](a.md#nope)\n[ok](a.md)\n",
  });
  try {
    const result = checkLinks(root);
    assert.equal(result.filesChecked, 2);
    assert.equal(result.errors.length, 2);
    assert.match(result.errors[0], /missing\.md/);
    assert.match(result.errors[1], /anchor not found/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
