/**
 * Shared expression contract for the workflow designer.
 *
 * The backend (`SpelExpressionService`) remains the authoritative parser and denylist. This module
 * mirrors the same limits and pattern list so the edge inspector can render instant, inline
 * feedback while the save path still re-validates every condition through the dry-run endpoint.
 */

/** Mirrors `SpelExpressionService.MAX_EXPRESSION_LENGTH`. */
export const MAX_CONDITION_LENGTH = 2000;

/** Mirrors `SpelExpressionService.FORBIDDEN_PATTERNS` in the same order. */
export const FORBIDDEN_CONDITION_PATTERNS = [
  "T(",
  "new ",
  ".class",
  "getClass",
  "System",
  "Runtime",
  "ProcessBuilder",
  "ClassLoader",
  "exec",
  "reflect",
] as const;

export const CONDITION_OPERATORS = [
  { value: "==", label: "equals (==)" },
  { value: "!=", label: "not equals (!=)" },
  { value: ">", label: "greater than (>)" },
  { value: ">=", label: "greater or equal (>=)" },
  { value: "<", label: "less than (<)" },
  { value: "<=", label: "less or equal (<=)" },
  { value: "contains", label: "contains" },
  { value: "hasRole", label: "has role" },
] as const;

export type ConditionOperator = (typeof CONDITION_OPERATORS)[number]["value"];

export const EXPRESSION_FUNCTIONS = [
  {
    name: "hasRole",
    usage: "#fn.hasRole(#role, 'MANAGER')",
    description: "True when the value equals the role",
  },
  {
    name: "hasAnyRole",
    usage: "#fn.hasAnyRole(#role, 'MANAGER', 'ADMIN')",
    description: "True when the value equals any of the roles",
  },
  {
    name: "matches",
    usage: "#fn.matches(#code, '^[A-Z]+$')",
    description: "True when the value matches the regex",
  },
  {
    name: "length",
    usage: "#fn.length(#name)",
    description: "Length of the value",
  },
  {
    name: "isEmpty",
    usage: "#fn.isEmpty(#name)",
    description: "True when the value is empty",
  },
  {
    name: "size",
    usage: "#fn.size(#items)",
    description: "Size of a collection",
  },
] as const;

/** Suggestions used when the definition does not declare variables yet. */
export const DEFAULT_WORKFLOW_VARIABLES = [
  "amount",
  "status",
  "role",
  "department",
  "priority",
] as const;

export interface ExpressionFieldError {
  field: "expression";
  message: string;
}

export interface ExpressionValidationResult {
  valid: boolean;
  errors: ExpressionFieldError[];
}

export type SuggestionKind = "variable" | "function";

export interface ExpressionSuggestion {
  label: string;
  insertText: string;
  kind: SuggestionKind;
  description: string;
}

function invalid(message: string): ExpressionValidationResult {
  return { valid: false, errors: [{ field: "expression", message }] };
}

/** Detects unbalanced parentheses, brackets and quotes before the backend parser sees them. */
function hasBalancedDelimiters(expression: string): boolean {
  const stack: string[] = [];
  let quote: string | null = null;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index];
    if (character === undefined) {
      continue;
    }
    if (quote !== null) {
      if (character === "\\") {
        index += 1;
        continue;
      }
      if (character === quote) {
        quote = null;
      }
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
      continue;
    }
    if (character === "(" || character === "[") {
      stack.push(character);
      continue;
    }
    if (character === ")" || character === "]") {
      const open = stack.pop();
      const expected = character === ")" ? "(" : "[";
      if (open !== expected) {
        return false;
      }
    }
  }
  return quote === null && stack.length === 0;
}

/**
 * Validates a condition against the shared contract.
 *
 * A blank condition is a valid, unconditional transition. Oversized, forbidden and syntactically
 * incomplete expressions produce one field-level error for inline rendering.
 */
export function validateExpression(expression: string): ExpressionValidationResult {
  if (expression.trim() === "") {
    return { valid: true, errors: [] };
  }
  if (expression.length > MAX_CONDITION_LENGTH) {
    return invalid(`Expression exceeds the maximum length of ${MAX_CONDITION_LENGTH} characters.`);
  }
  for (const pattern of FORBIDDEN_CONDITION_PATTERNS) {
    if (expression.includes(pattern)) {
      return invalid(`Expression contains a forbidden pattern: ${pattern}`);
    }
  }
  if (!hasBalancedDelimiters(expression)) {
    return invalid("Expression is invalid: check parentheses, brackets and quotes.");
  }
  return { valid: true, errors: [] };
}

function normalizeVariable(variable: string): string {
  const trimmed = variable.trim();
  if (trimmed === "") {
    return "#value";
  }
  return trimmed.startsWith("#") ? trimmed : `#${trimmed}`;
}

/** Quotes string literals while leaving numbers and booleans as SpEL literals. */
function literal(value: string): string {
  const trimmed = value.trim();
  if (trimmed === "") {
    return "''";
  }
  if (trimmed === "true" || trimmed === "false") {
    return trimmed;
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return trimmed;
  }
  return `'${trimmed.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/**
 * Builds a condition for the supported operators. `contains` is emitted as a method call and
 * `hasRole` delegates to the whitelisted `#fn` namespace, so the generated text stays inside the
 * sandbox at runtime.
 */
export function buildCondition(
  variable: string,
  operator: ConditionOperator,
  value: string,
): string {
  const target = normalizeVariable(variable);
  if (operator === "contains") {
    return `${target}.contains(${literal(value)})`;
  }
  if (operator === "hasRole") {
    return `#fn.hasRole(${target}, ${literal(value)})`;
  }
  return `${target} ${operator} ${literal(value)}`;
}

/** Returns the token around the caret, used to filter autocomplete suggestions. */
export function tokenAt(
  text: string,
  caret: number,
): { start: number; query: string } {
  const bounded = Math.max(0, Math.min(caret, text.length));
  const prefix = text.slice(0, bounded);
  const match = /([#A-Za-z0-9_.]*)$/.exec(prefix);
  const query = match?.[1] ?? "";
  return { start: bounded - query.length, query };
}

/** Suggests workflow variables and whitelisted functions for the current token. */
export function autocompleteSuggestions(
  text: string,
  caret: number,
  variables: readonly string[] = DEFAULT_WORKFLOW_VARIABLES,
): ExpressionSuggestion[] {
  const { query } = tokenAt(text, caret);
  const normalized = query.replace(/^#/, "").toLowerCase();

  const variableSuggestions: ExpressionSuggestion[] = variables
    .map((name) => ({
      label: `#${name}`,
      insertText: `#${name}`,
      kind: "variable" as const,
      description: "Workflow variable",
    }))
    .filter((suggestion) => normalized === "" || suggestion.label.slice(1).toLowerCase().includes(normalized));

  const functionSuggestions: ExpressionSuggestion[] = EXPRESSION_FUNCTIONS.map((fn) => ({
    label: `#fn.${fn.name}`,
    insertText: fn.usage,
    kind: "function" as const,
    description: fn.description,
  })).filter(
    (suggestion) =>
      normalized === "" ||
      `fn.${suggestion.label.slice(4)}`.toLowerCase().includes(normalized) ||
      suggestion.label.slice(4).toLowerCase().includes(normalized),
  );

  return [...variableSuggestions, ...functionSuggestions].slice(0, 8);
}

/** Replaces the token at the caret with the selected suggestion. */
export function applySuggestion(
  text: string,
  caret: number,
  suggestion: ExpressionSuggestion,
): { text: string; caret: number } {
  const { start } = tokenAt(text, caret);
  const bounded = Math.max(0, Math.min(caret, text.length));
  return {
    text: `${text.slice(0, start)}${suggestion.insertText}${text.slice(bounded)}`,
    caret: start + suggestion.insertText.length,
  };
}
