"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { validateWorkflowExpression, type ExpressionValidationDto } from "../../lib/api";
import {
  CONDITION_OPERATORS,
  MAX_CONDITION_LENGTH,
  applySuggestion,
  autocompleteSuggestions,
  buildCondition,
  validateExpression,
  type ConditionOperator,
  type ExpressionSuggestion,
} from "../../lib/workflowExpressions";
import { conditionOf, type WorkflowEdge } from "../../lib/workflowGraph";

type ValidationStatus = "validating" | "done" | "error";

interface ServerValidationState {
  expression: string;
  status: ValidationStatus;
  result: ExpressionValidationDto | null;
}

interface EdgeConditionEditorProps {
  edge: WorkflowEdge;
  variables: readonly string[];
  onChangeCondition: (condition: string) => void;
  onClose: () => void;
  onDuplicate: () => void;
}

/**
 * Edge inspector with a plain-text condition editor, an expression builder, autocomplete and
 * inline validation. The backend dry-run is authoritative; the client mirror exists for instant
 * feedback and to block saving obviously invalid graphs.
 */
export default function EdgeConditionEditor({
  edge,
  variables,
  onChangeCondition,
  onClose,
  onDuplicate,
}: EdgeConditionEditorProps) {
  const condition = conditionOf(edge);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const [variable, setVariable] = useState(variables[0] ?? "amount");
  const [operator, setOperator] = useState<ConditionOperator>("==");
  const [value, setValue] = useState("");
  const [caret, setCaret] = useState(condition.length);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [serverState, setServerState] = useState<ServerValidationState | null>(null);
  const [retryToken, setRetryToken] = useState(0);

  const clientValidation = validateExpression(condition);
  const clientError = clientValidation.errors[0]?.message ?? null;
  const currentState = serverState?.expression === condition ? serverState : null;
  const status = currentState?.status ?? null;
  const serverResult = currentState?.result ?? null;
  const serverError =
    serverResult !== null && !serverResult.valid
      ? (serverResult.errors[0]?.message ?? "Expression is invalid.")
      : null;
  const error = clientError ?? serverError;
  const suggestions = suggestionsOpen ? autocompleteSuggestions(condition, caret, variables) : [];
  const showSuggestions = suggestionsOpen && suggestions.length > 0;

  useEffect(() => {
    if (condition.trim() === "" || clientError !== null) {
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      if (cancelled) {
        return;
      }
      setServerState({ expression: condition, status: "validating", result: null });
      validateWorkflowExpression(condition)
        .then((result) => {
          if (!cancelled) {
            setServerState({ expression: condition, status: "done", result });
          }
        })
        .catch(() => {
          if (!cancelled) {
            setServerState({ expression: condition, status: "error", result: null });
          }
        });
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [condition, clientError, retryToken]);

  const updateCondition = (next: string, nextCaret: number) => {
    onChangeCondition(next);
    setCaret(nextCaret);
    setActiveIndex(0);
    const prefix = next.slice(0, nextCaret);
    setSuggestionsOpen(prefix.endsWith("#") || /#fn\.[A-Za-z0-9_]*$/.test(prefix));
  };

  const applySuggestionToText = (suggestion: ExpressionSuggestion) => {
    const result = applySuggestion(condition, caret, suggestion);
    updateCondition(result.text, result.caret);
    setSuggestionsOpen(false);
    setTimeout(() => {
      const element = textareaRef.current;
      if (element) {
        element.focus();
        element.setSelectionRange(result.caret, result.caret);
      }
    }, 0);
  };

  const insertBuiltCondition = () => {
    const built = buildCondition(variable, operator, value);
    const element = textareaRef.current;
    const start = element?.selectionStart ?? condition.length;
    const end = element?.selectionEnd ?? condition.length;
    const needsSpace = start > 0 && !/\s$/.test(condition.slice(0, start));
    const insertion = `${needsSpace ? " " : ""}${built}`;
    const next = `${condition.slice(0, start)}${insertion}${condition.slice(end)}`;
    updateCondition(next, start + insertion.length);
    setSuggestionsOpen(false);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      setSuggestionsOpen(false);
      onClose();
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (!showSuggestions) {
        setSuggestionsOpen(true);
        setActiveIndex(0);
        return;
      }
      setActiveIndex((index) => Math.min(index + 1, suggestions.length - 1));
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => Math.max(index - 1, 0));
      return;
    }
    if (event.key === "Enter" && showSuggestions) {
      const suggestion = suggestions[activeIndex];
      if (suggestion) {
        event.preventDefault();
        applySuggestionToText(suggestion);
      }
    }
  };

  return (
    <aside
      aria-labelledby="edge-condition-heading"
      style={{ width: 320, border: "1px solid #e2e8f0", borderRadius: 8, padding: 12 }}
    >
      <h2 id="edge-condition-heading" style={{ fontSize: 16, fontWeight: 700 }}>
        Edge condition
      </h2>
      <p style={{ color: "#6b7280", fontSize: 12, marginTop: 0 }}>
        From {edge.source} to {edge.target}
      </p>

      <label htmlFor="edge-condition">Condition expression</label>
      <textarea
        id="edge-condition"
        ref={textareaRef}
        role="combobox"
        aria-expanded={showSuggestions}
        aria-controls="condition-suggestions"
        aria-autocomplete="list"
        aria-activedescendant={showSuggestions ? `condition-suggestion-${activeIndex}` : undefined}
        aria-describedby={
          error === null ? "edge-condition-help" : "edge-condition-help edge-condition-error"
        }
        aria-invalid={error === null ? undefined : true}
        value={condition}
        rows={4}
        placeholder="#amount > 100"
        onChange={(event) => {
          const next = event.target.value;
          updateCondition(next, event.target.selectionStart);
        }}
        onSelect={(event) => setCaret(event.currentTarget.selectionStart)}
        onKeyDown={handleKeyDown}
        onBlur={() => setSuggestionsOpen(false)}
        style={{ width: "100%", display: "block", fontFamily: "monospace", marginBottom: 4 }}
      />
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          fontSize: 12,
          color: "#6b7280",
        }}
      >
        <span id="edge-condition-help">
          Empty means unconditional. Type # for variables or #fn. for functions.
        </span>
        <span aria-live="polite">
          {condition.length}/{MAX_CONDITION_LENGTH}
        </span>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
        <button
          type="button"
          onClick={() => {
            setSuggestionsOpen(true);
            setActiveIndex(0);
          }}
          aria-label="Show condition suggestions"
        >
          Suggestions
        </button>
      </div>

      {showSuggestions ? (
        <ul
          id="condition-suggestions"
          role="listbox"
          aria-label="Condition suggestions"
          style={{
            listStyle: "none",
            padding: 4,
            margin: "4px 0 8px",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            maxHeight: 180,
            overflowY: "auto",
          }}
        >
          {suggestions.map((suggestion, index) => (
            <li key={`${suggestion.kind}-${suggestion.label}`} role="presentation">
              <button
                type="button"
                id={`condition-suggestion-${index}`}
                role="option"
                aria-selected={index === activeIndex}
                tabIndex={-1}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => applySuggestionToText(suggestion)}
                style={{
                  display: "block",
                  width: "100%",
                  textAlign: "left",
                  background: index === activeIndex ? "#e0f2fe" : "transparent",
                  border: "none",
                  padding: "4px 6px",
                  cursor: "pointer",
                }}
              >
                <span style={{ fontFamily: "monospace" }}>{suggestion.label}</span>{" "}
                <span style={{ color: "#6b7280", fontSize: 11 }}>{suggestion.description}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {clientError !== null ? (
        <p id="edge-condition-error" role="alert" style={{ color: "#b91c1c" }}>
          {clientError}
        </p>
      ) : null}

      {status === "validating" ? (
        <p role="status" style={{ color: "#6b7280" }}>
          Checking expression…
        </p>
      ) : null}
      {status === "done" && serverResult?.valid && condition.trim() !== "" ? (
        <p role="status" style={{ color: "#15803d" }}>
          Expression is valid.
        </p>
      ) : null}
      {status === "done" && serverError !== null ? (
        <p role="alert" style={{ color: "#b91c1c" }}>
          {serverError}
        </p>
      ) : null}
      {status === "error" ? (
        <p role="alert">
          Validation failed. Check your connection and retry.{" "}
          <button type="button" onClick={() => setRetryToken((token) => token + 1)}>
            Retry validation
          </button>
        </p>
      ) : null}

      <fieldset style={{ border: "1px solid #e2e8f0", borderRadius: 6, marginTop: 12 }}>
        <legend style={{ fontSize: 13, fontWeight: 700 }}>Expression builder</legend>
        <label htmlFor="condition-variable">Variable</label>
        <select
          id="condition-variable"
          value={variable}
          onChange={(event) => setVariable(event.target.value)}
          style={{ width: "100%", display: "block" }}
        >
          {variables.map((name) => (
            <option key={name} value={name}>
              #{name}
            </option>
          ))}
        </select>

        <label htmlFor="condition-operator">Operator</label>
        <select
          id="condition-operator"
          value={operator}
          onChange={(event) => setOperator(event.target.value as ConditionOperator)}
          style={{ width: "100%", display: "block" }}
        >
          {CONDITION_OPERATORS.map((entry) => (
            <option key={entry.value} value={entry.value}>
              {entry.label}
            </option>
          ))}
        </select>

        <label htmlFor="condition-value">Value</label>
        <input
          id="condition-value"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          style={{ width: "100%", display: "block" }}
        />

        <button type="button" onClick={insertBuiltCondition} style={{ marginTop: 8 }}>
          Insert condition
        </button>
      </fieldset>

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <button type="button" onClick={onDuplicate} aria-label="Duplicate conditional edge">
          Duplicate edge
        </button>
        <button type="button" onClick={onClose} aria-label="Close edge inspector">
          Close
        </button>
      </div>
    </aside>
  );
}
