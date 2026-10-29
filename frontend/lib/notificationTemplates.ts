const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g;

function occurrences(text: string, needle: string): number {
  let count = 0;
  let index = text.indexOf(needle);
  while (index >= 0) {
    count += 1;
    index = text.indexOf(needle, index + needle.length);
  }
  return count;
}

/** Mirrors the backend placeholder syntax rules so the editor fails fast. */
export function validateTemplateText(text: string): string | null {
  const opens = occurrences(text, "{{");
  const closes = occurrences(text, "}}");
  if (opens !== closes) {
    return "Placeholders are unbalanced: every {{ must be closed with }}.";
  }
  const matched = text.match(PLACEHOLDER) ?? [];
  if (matched.length !== opens) {
    return "Placeholders must look like {{name}} using letters, digits, '.', '_' or '-'.";
  }
  return null;
}

/** Rejects placeholders that the supplied variables do not satisfy. */
export function validateTemplateVariables(
  text: string,
  variables: Record<string, string>,
): string | null {
  const unknown = [...text.matchAll(PLACEHOLDER)]
    .map((match) => match[1] ?? "")
    .filter((key) => key !== "" && !(key in variables));
  if (unknown.length > 0) {
    return `Unknown placeholders: ${unknown.join(", ")}.`;
  }
  return null;
}

/** Parses the variables JSON, returning an error message instead of throwing. */
export function parseVariables(
  variablesJson: string,
): { variables: Record<string, string> } | { error: string } {
  if (variablesJson.trim() === "") {
    return { variables: {} };
  }
  try {
    const parsed: unknown = JSON.parse(variablesJson);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { error: "Variables must be a JSON object." };
    }
    const variables: Record<string, string> = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      variables[key] = typeof value === "string" ? value : JSON.stringify(value);
    }
    return { variables };
  } catch {
    return { error: "Variables must be valid JSON." };
  }
}
