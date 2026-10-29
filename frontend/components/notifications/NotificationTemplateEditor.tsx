"use client";

import { useEffect, useState } from "react";
import type { NotificationTemplatePreviewDto } from "../../lib/api";
import {
  useCreateNotificationTemplate,
  usePreviewNotificationTemplate,
} from "../../lib/hooks/useNotificationTemplates";
import {
  parseVariables,
  validateTemplateText,
  validateTemplateVariables,
} from "../../lib/notificationTemplates";

/** Creates a tenant template with placeholder validation, preview and an unsaved-change guard. */
export default function NotificationTemplateEditor() {
  const [templateKey, setTemplateKey] = useState("");
  const [locale, setLocale] = useState("en");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [variablesJson, setVariablesJson] = useState('{\n  "name": "Ada"\n}');
  const [previewResult, setPreviewResult] = useState<NotificationTemplatePreviewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const preview = usePreviewNotificationTemplate();
  const create = useCreateNotificationTemplate();
  const dirty = templateKey !== "" || subject !== "" || body !== "";

  useEffect(() => {
    if (!dirty) {
      return;
    }
    const guard = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [dirty]);

  const validationError = (): string | null => {
    const syntax = validateTemplateText(subject) ?? validateTemplateText(body);
    if (syntax) {
      return syntax;
    }
    const parsed = parseVariables(variablesJson);
    if ("error" in parsed) {
      return parsed.error;
    }
    return (
      validateTemplateVariables(subject, parsed.variables) ??
      validateTemplateVariables(body, parsed.variables)
    );
  };

  const onPreview = () => {
    setError(null);
    setNotice(null);
    setPreviewResult(null);
    const problem = validationError();
    if (problem) {
      setError(problem);
      return;
    }
    preview.mutate(
      { templateKey, locale, subject, body, variablesJson },
      {
        onSuccess: (data) => setPreviewResult(data),
        onError: (cause) =>
          setError(cause instanceof Error ? cause.message : "Preview failed."),
      },
    );
  };

  const onSave = () => {
    setError(null);
    setNotice(null);
    const problem = validationError();
    if (problem) {
      setError(problem);
      return;
    }
    if (templateKey.trim() === "") {
      setError("Template key is required.");
      return;
    }
    create.mutate(
      { templateKey, locale, subject, body },
      {
        onSuccess: () => setNotice("Template saved."),
        onError: (cause) => setError(cause instanceof Error ? cause.message : "Save failed."),
      },
    );
  };

  return (
    <section aria-labelledby="template-editor-heading" style={{ maxWidth: 720 }}>
      <h1 id="template-editor-heading" style={{ fontSize: 24, fontWeight: 700 }}>
        Notification templates
      </h1>

      {error ? <p role="alert">{error}</p> : null}
      {notice ? (
        <p role="status" aria-live="polite">
          {notice}
        </p>
      ) : null}

      <div style={{ display: "grid", gap: 8 }}>
        <label htmlFor="template-key">Template key</label>
        <input
          id="template-key"
          value={templateKey}
          onChange={(event) => setTemplateKey(event.target.value)}
        />
        <label htmlFor="template-locale">Locale</label>
        <select
          id="template-locale"
          value={locale}
          onChange={(event) => setLocale(event.target.value)}
        >
          <option value="en">en</option>
          <option value="tr">tr</option>
        </select>
        <label htmlFor="template-subject">Subject</label>
        <input
          id="template-subject"
          value={subject}
          onChange={(event) => setSubject(event.target.value)}
        />
        <label htmlFor="template-body">Body</label>
        <textarea
          id="template-body"
          rows={4}
          value={body}
          onChange={(event) => setBody(event.target.value)}
        />
        <label htmlFor="template-variables">Variables JSON</label>
        <textarea
          id="template-variables"
          rows={4}
          value={variablesJson}
          onChange={(event) => setVariablesJson(event.target.value)}
        />
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" onClick={onPreview} aria-label="Preview template">
            Preview
          </button>
          <button type="button" onClick={onSave} aria-label="Save template">
            Save
          </button>
        </div>
      </div>

      {previewResult ? (
        <section aria-labelledby="template-preview-heading" style={{ marginTop: 16 }}>
          <h2 id="template-preview-heading" style={{ fontSize: 18 }}>
            Preview
          </h2>
          <p data-testid="preview-subject">{previewResult.subject}</p>
          <p data-testid="preview-body" aria-live="polite">
            {previewResult.body}
          </p>
          <p>Placeholders: {previewResult.placeholders.join(", ") || "none"}</p>
        </section>
      ) : null}
    </section>
  );
}
