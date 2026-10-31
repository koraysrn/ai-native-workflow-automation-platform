import {
  DEFAULT_WORKFLOW_VARIABLES,
  MAX_CONDITION_LENGTH,
  applySuggestion,
  autocompleteSuggestions,
  buildCondition,
  tokenAt,
  validateExpression,
} from "../workflowExpressions";

describe("validateExpression", () => {
  it("treats an empty or whitespace condition as unconditional and valid", () => {
    expect(validateExpression("")).toEqual({ valid: true, errors: [] });
    expect(validateExpression("   ")).toEqual({ valid: true, errors: [] });
  });

  it("accepts a well-formed SpEL comparison", () => {
    expect(validateExpression("#amount > 100 && #role == 'MANAGER'")).toEqual({
      valid: true,
      errors: [],
    });
  });

  it("rejects forbidden patterns with the backend wording", () => {
    expect(validateExpression("T(java.lang.Runtime).getRuntime()")).toEqual({
      valid: false,
      errors: [{ field: "expression", message: "Expression contains a forbidden pattern: T(" }],
    });
    expect(validateExpression("System.exit(0)").errors[0]?.message).toBe(
      "Expression contains a forbidden pattern: System",
    );
  });

  it("rejects expressions longer than the shared limit", () => {
    const result = validateExpression("a".repeat(MAX_CONDITION_LENGTH + 1));

    expect(result.valid).toBe(false);
    expect(result.errors[0]?.message).toContain("maximum length");
  });

  it("accepts an expression exactly at the limit", () => {
    expect(validateExpression("a".repeat(MAX_CONDITION_LENGTH)).valid).toBe(true);
  });

  it("rejects unbalanced parentheses, brackets and quotes", () => {
    expect(validateExpression("#amount > (100").errors[0]?.message).toContain("invalid");
    expect(validateExpression("#items[0 == 1").errors[0]?.message).toContain("invalid");
    expect(validateExpression("#name == 'Ada").errors[0]?.message).toContain("invalid");
  });

  it("ignores delimiters inside quoted strings", () => {
    expect(validateExpression("#name == '(unbalanced").valid).toBe(false);
    expect(validateExpression("#name == '(balanced)'").valid).toBe(true);
  });
});

describe("buildCondition", () => {
  it("builds numeric and string comparisons", () => {
    expect(buildCondition("amount", ">", "100")).toBe("#amount > 100");
    expect(buildCondition("#status", "==", "APPROVED")).toBe("#status == 'APPROVED'");
  });

  it("escapes quotes and backslashes in string literals", () => {
    expect(buildCondition("note", "==", "O'Reilly")).toBe("#note == 'O\\'Reilly'");
    expect(buildCondition("path", "==", "C:\\tmp")).toBe("#path == 'C:\\\\tmp'");
  });

  it("builds a contains call for string membership", () => {
    expect(buildCondition("department", "contains", "finance")).toBe(
      "#department.contains('finance')",
    );
  });

  it("builds a hasRole call through the whitelisted fn namespace", () => {
    expect(buildCondition("role", "hasRole", "MANAGER")).toBe(
      "#fn.hasRole(#role, 'MANAGER')",
    );
  });

  it("keeps booleans and decimals unquoted", () => {
    expect(buildCondition("active", "==", "true")).toBe("#active == true");
    expect(buildCondition("amount", ">=", "10.5")).toBe("#amount >= 10.5");
  });

  it("falls back to an empty string literal for a blank value", () => {
    expect(buildCondition("status", "==", "   ")).toBe("#status == ''");
  });

  it("normalizes a variable without the leading hash", () => {
    expect(buildCondition(" role ", "==", "ADMIN")).toBe("#role == 'ADMIN'");
    expect(buildCondition("", "==", "x")).toBe("#value == 'x'");
  });
});

describe("autocomplete", () => {
  it("finds the token around the caret", () => {
    expect(tokenAt("#amount > #ro", 13)).toEqual({ start: 10, query: "#ro" });
    expect(tokenAt("#amount > ", 10)).toEqual({ start: 10, query: "" });
    expect(tokenAt("#amount", 999)).toEqual({ start: 0, query: "#amount" });
  });

  it("suggests variables first and functions when the token starts with #fn", () => {
    const variables = autocompleteSuggestions("", 0, ["amount", "role"]);
    expect(variables.map((suggestion) => suggestion.label)).toEqual([
      "#amount",
      "#role",
      "#fn.hasRole",
      "#fn.hasAnyRole",
      "#fn.matches",
      "#fn.length",
      "#fn.isEmpty",
      "#fn.size",
    ]);

    const functions = autocompleteSuggestions("#fn.", 4, ["amount", "role"]);
    expect(functions.every((suggestion) => suggestion.kind === "function")).toBe(true);
    expect(functions[0]?.insertText).toContain("#fn.hasRole(");
  });

  it("filters by the typed prefix", () => {
    const suggestions = autocompleteSuggestions("#am", 3, ["amount", "role"]);
    expect(suggestions.map((suggestion) => suggestion.label)).toEqual(["#amount"]);
  });

  it("uses the default variables when none are supplied", () => {
    const suggestions = autocompleteSuggestions("", 0);
    expect(suggestions[0]?.label).toBe(`#${DEFAULT_WORKFLOW_VARIABLES[0]}`);
  });

  it("inserts a suggestion at the caret and keeps the surrounding text", () => {
    const suggestion = {
      label: "#role",
      insertText: "#role",
      kind: "variable" as const,
      description: "Workflow variable",
    };
    expect(applySuggestion("#amount > #ro && true", 13, suggestion)).toEqual({
      text: "#amount > #role && true",
      caret: 15,
    });
  });
});
