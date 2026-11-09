#!/usr/bin/env node
/**
 * Documentation link checker (issue #185).
 *
 * Checks every relative Markdown link (and every Markdown/`#anchor` pair) in
 * the repository. External `http(s)` links are intentionally not fetched so the
 * check is deterministic and does not depend on network access.
 *
 * Usage: node scripts/check-doc-links.mjs [--root <dir>]
 */

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

const IGNORED_FILES = new Set([
  // Personal planning document excluded from the repository (see ADR-001).
  "GELISTIRME_PLANI.md",
]);

const IGNORED_DIRS = new Set([
  ".git",
  "node_modules",
  ".next",
  "target",
  ".venv",
  "build",
  "dist",
  "coverage",
  "playwright-report",
  "test-results",
  "a11y-report",
]);

/** Collects Markdown files under a root, skipping generated directories. */
export function collectMarkdownFiles(root) {
  const files = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) {
        if (!IGNORED_DIRS.has(entry.name)) {
          walk(join(dir, entry.name));
        }
      } else if (entry.isFile() && entry.name.endsWith(".md") && !IGNORED_FILES.has(entry.name)) {
        files.push(join(dir, entry.name));
      }
    }
  };
  walk(root);
  return files.sort();
}

/** Extracts local links from Markdown, ignoring code fences and external URLs. */
export function extractLocalLinks(markdown) {
  const withoutFences = markdown.replace(/```[\s\S]*?```/g, "").replace(/`[^`]*`/g, "");
  const links = [];
  const pattern = /\[[^\]]*\]\(([^)]+)\)/g;
  let match;
  while ((match = pattern.exec(withoutFences)) !== null) {
    const raw = match[1].trim();
    const target = raw.startsWith("<") ? raw.slice(1, raw.indexOf(">")) : raw.split(/\s+/)[0];
    if (!target) {
      continue;
    }
    if (/^(?:https?:|mailto:|tel:|data:)/i.test(target)) {
      continue;
    }
    links.push(target);
  }
  return links;
}

/** Converts a heading into the GitHub-style anchor slug. */
export function slugifyHeading(heading) {
  // Mirrors github-slugger: strip punctuation (including em dashes), then turn
  // each remaining whitespace character into a hyphen. Do not collapse runs:
  // "O21 — Trivy" must become "o21--trivy" exactly like GitHub.
  return heading
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .replace(/\s/g, "-");
}

/** Returns the anchors defined by a Markdown document. */
export function markdownAnchors(markdown) {
  const anchors = new Set();
  for (const line of markdown.split("\n")) {
    const match = /^#{1,6}\s+(.*)$/.exec(line);
    if (match) {
      anchors.add(slugifyHeading(match[1]));
    }
  }
  return anchors;
}

/** Validates one link; returns an error string or undefined. */
export function validateLink(fromFile, target, root) {
  const [pathPart, anchor] = target.split("#");
  let rawPath = pathPart ? decodeURIComponent(pathPart) : "";
  let resolved = rawPath ? resolve(dirname(fromFile), rawPath) : fromFile;

  // GitHub supports `path:line` links. Retry without the line suffix when the
  // literal path does not exist.
  if (rawPath && !existsSync(resolved)) {
    const lineSuffix = /^(.*\.\w+):\d+$/.exec(rawPath);
    if (lineSuffix) {
      rawPath = lineSuffix[1];
      resolved = resolve(dirname(fromFile), rawPath);
    }
  }

  if (rawPath && !existsSync(resolved)) {
    return `${relative(root, fromFile)} -> ${target}: target does not exist`;
  }
  if (anchor) {
    const anchorFile = pathPart ? resolved : fromFile;
    if (!anchorFile.endsWith(".md")) {
      return undefined;
    }
    const anchors = markdownAnchors(readFileSync(anchorFile, "utf8"));
    if (!anchors.has(anchor.toLowerCase())) {
      return `${relative(root, fromFile)} -> ${target}: anchor not found`;
    }
  }
  return undefined;
}

export function checkLinks(root) {
  const errors = [];
  const files = collectMarkdownFiles(root);
  for (const file of files) {
    for (const link of extractLocalLinks(readFileSync(file, "utf8"))) {
      const error = validateLink(file, link, root);
      if (error) {
        errors.push(error);
      }
    }
  }
  return { filesChecked: files.length, errors };
}

export function main(argv = process.argv.slice(2), cwd = process.cwd(), log = console.log) {
  const rootIndex = argv.indexOf("--root");
  const root = rootIndex >= 0 ? resolve(argv[rootIndex + 1]) : cwd;
  const { filesChecked, errors } = checkLinks(root);
  if (errors.length > 0) {
    for (const error of errors) {
      log(`FAIL: ${error}`);
    }
    log(`${errors.length} broken link(s) in ${filesChecked} Markdown file(s).`);
    return 1;
  }
  log(`OK: ${filesChecked} Markdown file(s), all local links resolve.`);
  return 0;
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  process.exitCode = main();
}
