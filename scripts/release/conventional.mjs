/**
 * Conventional Commit parsing and version planning shared by the release
 * dry-run and the CI commit lint. The bump rules mirror release-please:
 * a breaking change is major, `feat` is minor, everything else is a patch.
 */

export const DEFAULT_SECTIONS = [
  { type: "feat", section: "Features", hidden: false },
  { type: "fix", section: "Bug Fixes", hidden: false },
  { type: "perf", section: "Performance Improvements", hidden: false },
  { type: "revert", section: "Reverts", hidden: false },
  { type: "docs", section: "Documentation", hidden: true },
  { type: "style", section: "Styles", hidden: true },
  { type: "chore", section: "Miscellaneous Chores", hidden: true },
  { type: "refactor", section: "Code Refactoring", hidden: true },
  { type: "test", section: "Tests", hidden: true },
  { type: "build", section: "Build System", hidden: true },
  { type: "ci", section: "Continuous Integration", hidden: true },
];

const KNOWN_TYPES = new Set(DEFAULT_SECTIONS.map((entry) => entry.type));
const HEADER_PATTERN = /^([a-z]+)(?:\(([^()]+)\))?(!)?: (.+)$/;
const BREAKING_FOOTER = /^BREAKING[ -]CHANGE:\s*(.+)$/gm;

export class NonConventionalCommitError extends Error {
  constructor(commits) {
    super(`${commits.length} commit(s) do not follow Conventional Commits`);
    this.name = "NonConventionalCommitError";
    this.commits = commits;
  }
}

/** Parse one raw commit message into a normalised commit record. */
export function parseCommit({ sha = "", message = "" } = {}) {
  const normalised = String(message).replace(/\r\n/g, "\n");
  const [rawHeader = "", ...rest] = normalised.split("\n");
  const header = rawHeader.trim();
  const match = HEADER_PATTERN.exec(header);

  if (!match) {
    return {
      sha,
      conventional: false,
      type: null,
      scope: null,
      description: header,
      breaking: false,
      breakingNotes: [],
      header,
    };
  }

  const [, type, scope = null, bang, description] = match;
  if (!KNOWN_TYPES.has(type)) {
    return {
      sha,
      conventional: false,
      type,
      scope,
      description: description.trim(),
      breaking: false,
      breakingNotes: [],
      header,
    };
  }

  const body = rest.join("\n");
  const breakingNotes = [...body.matchAll(BREAKING_FOOTER)]
    .map((footer) => footer[1].trim())
    .filter(Boolean);

  return {
    sha,
    conventional: true,
    type,
    scope,
    description: description.trim(),
    breaking: Boolean(bang) || breakingNotes.length > 0,
    breakingNotes,
    header,
  };
}

/** Return the release type for a list of already parsed commits. */
export function computeBump(commits) {
  if (commits.some((commit) => commit.breaking)) {
    return "major";
  }
  if (commits.some((commit) => commit.type === "feat")) {
    return "minor";
  }
  return "patch";
}

/** Apply a semantic version bump to a `major.minor.patch` version. */
export function nextVersion(currentVersion, bump) {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?$/.exec(
    String(currentVersion),
  );
  if (!match) {
    throw new Error(
      `invalid current version '${currentVersion}'; expected major.minor.patch`,
    );
  }

  let major = Number(match[1]);
  let minor = Number(match[2]);
  let patch = Number(match[3]);

  if (bump === "major") {
    major += 1;
    minor = 0;
    patch = 0;
  } else if (bump === "minor") {
    minor += 1;
    patch = 0;
  } else if (bump === "patch") {
    patch += 1;
  } else {
    throw new Error(`unknown bump type '${bump}'`);
  }

  return `${major}.${minor}.${patch}`;
}

/** Merge the release-please config sections with the default sections. */
export function resolveSections(configSections) {
  if (!Array.isArray(configSections) || configSections.length === 0) {
    return DEFAULT_SECTIONS;
  }
  return configSections.map((entry) => ({
    type: String(entry.type),
    section: String(entry.section ?? "Other Changes"),
    hidden: Boolean(entry.hidden),
  }));
}

function formatCommit(commit) {
  const scope = commit.scope ? `**${commit.scope}:** ` : "";
  const sha = commit.sha ? ` (${commit.sha.slice(0, 7)})` : "";
  return `* ${scope}${commit.description}${sha}`;
}

/** Render the CHANGELOG entry that release-please would create. */
export function renderChangelogEntry({
  version,
  date,
  commits,
  sections = DEFAULT_SECTIONS,
}) {
  const lines = [`## [${version}] - ${date}`, ""];
  const breaking = commits.flatMap((commit) => {
    if (!commit.breaking) {
      return [];
    }
    const notes = commit.breakingNotes.length > 0
      ? commit.breakingNotes
      : [commit.description];
    return notes.map((note) => {
      const scope = commit.scope ? `**${commit.scope}:** ` : "";
      return `* ${scope}${note}`;
    });
  });

  if (breaking.length > 0) {
    lines.push("### \u26a0 BREAKING CHANGES", "", ...breaking, "");
  }

  for (const entry of sections) {
    if (entry.hidden) {
      continue;
    }
    const grouped = commits.filter((commit) => commit.type === entry.type);
    if (grouped.length === 0) {
      continue;
    }
    lines.push(`### ${entry.section}`, "", ...grouped.map(formatCommit), "");
  }

  return lines.join("\n").trimEnd() + "\n";
}
