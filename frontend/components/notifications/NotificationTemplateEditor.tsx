"use client";

import { useEffect, useState } from "react";
import type { NotificationTemplateDto, NotificationTemplatePreviewDto } from "../../lib/api";
import {
  useActivateNotificationTemplate,
  useArchiveNotificationTemplate,
  useCreateNotificationTemplate,
  useNotificationTemplates,
  usePreviewNotificationTemplate,
  useUpdateNotificationTemplate,
} from "../../lib/hooks/useNotificationTemplates";
import {
  parseVariables,
  validateTemplateText,
  validateTemplateVariables,
} from "../../lib/notificationTemplates";

const KEY_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;
const LOCALE_PATTERN = /^[A-Za-z]{2}([_-][A-Za-z]{2})?$/;

interface EditorState {
  templateKey: string;
  locale: string;
  subject: string;
  body: string;
}

const EMPTY_TEMPLATE: EditorState = {
  templateKey: "",
  locale: "en",
  subject: "",
  body: "",
};

function snapshot(template: NotificationTemplateDto): EditorState {
  return {
    templateKey: template.templateKey,
    locale: template.locale,
    subject: template.subject,
    body: template.body,
  };
}

/** Creates and edits tenant templates with validation, preview and an unsaved-change guard. */
export default function NotificationTemplateEditor() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedActive, setSelectedActive] = useState(false);
  const [expectedVersion, setExpectedVersion] = useState<number | null>(null);
  const [templateKey, setTemplateKey] = useState(EMPTY_TEMPLATE.templateKey);
  const [locale, setLocale] = useState(EMPTY_TEMPLATE.locale);
  const [subject, setSubject] = useState(EMPTY_TEMPLATE.subject);
  const [body, setBody] = useState(EMPTY_TEMPLATE.body);
  const [initial, setInitial] = useState<EditorState>(EMPTY_TEMPLATE);
  const [variablesJson, setVariablesJson] = useState('{\n  "name": "Ada"\n}');
  const [previewResult, setPreviewResult] = useState<NotificationTemplatePreviewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const templatesQuery = useNotificationTemplates();
  const preview = usePreviewNotificationTemplate();
  const create = useCreateNotificationTemplate();
  const update = useUpdateNotificationTemplate();
  const archive = useArchiveNotificationTemplate();
  const activate = useActivateNotificationTemplate();
  const available = templatesQuery.data ?? [];

  const dirty =
    templateKey !== initial.templateKey ||
    locale !== initial.locale ||
    subject !== initial.subject ||
    body !== initial.body;

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
    if (!LOCALE_PATTERN.test(locale.trim())) {
      return "Locale must look like 'en' or 'en-US'.";
    }
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

  const applySaved = (template: NotificationTemplateDto, message: string) => {
    const next = snapshot(template);
    setSelectedId(template.id);
    setSelectedActive(template.active);
    setExpectedVersion(template.version);
    setTemplateKey(next.templateKey);
    setLocale(next.locale);
    setSubject(next.subject);
    setBody(next.body);
    setInitial(next);
    setNotice(message);
  };

  const chooseTemplate = (template: NotificationTemplateDto) => {
    if (dirty && !window.confirm("Discard unsaved changes?")) {
      return;
    }
    const next = snapshot(template);
    setSelectedId(template.id);
    setSelectedActive(template.active);
    setExpectedVersion(template.version);
    setTemplateKey(next.templateKey);
    setLocale(next.locale);
    setSubject(next.subject);
    setBody(next.body);
    setInitial(next);
    setPreviewResult(null);
    setError(null);
    setNotice(null);
  };

  const startNewTemplate = () => {
    if (dirty && !window.confirm("Discard unsaved changes?")) {
      return;
    }
    setSelectedId(null);
    setSelectedActive(false);
    setExpectedVersion(null);
    setTemplateKey(EMPTY_TEMPLATE.templateKey);
    setLocale(EMPTY_TEMPLATE.locale);
    setSubject(EMPTY_TEMPLATE.subject);
    setBody(EMPTY_TEMPLATE.body);
    setInitial(EMPTY_TEMPLATE);
    setPreviewResult(null);
    setError(null);
    setNotice(null);
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
    const parsed = parseVariables(variablesJson);
    if ("error" in parsed) {
      setError(parsed.error);
      return;
    }
    preview.mutate(
      {
        templateKey,
        locale,
        subject,
        body,
        variablesJson: JSON.stringify(parsed.variables),
      },
      {
        onSuccess: (data) => setPreviewResult(data),
        onError: (cause) => setError(cause instanceof Error ? cause.message : "Preview failed."),
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
    const key = templateKey.trim();
    if (!KEY_PATTERN.test(key)) {
      setError("Template key must use lowercase letters, digits, '.', '_' or '-'.");
      return;
    }
    if (selectedId && expectedVersion !== null) {
      update.mutate(
        { id: selectedId, payload: { expectedVersion, subject, body } },
        {
          onSuccess: (template) => applySaved(template, "Template saved."),
          onError: (cause) => setError(cause instanceof Error ? cause.message : "Save failed."),
        },
      );
      return;
    }
    create.mutate(
      { templateKey: key, locale, subject, body },
      {
        onSuccess: (template) => applySaved(template, "Template saved."),
        onError: (cause) => setError(cause instanceof Error ? cause.message : "Save failed."),
      },
    );
  };

  const onArchive = () => {
    if (!selectedId) {
      return;
    }
    setError(null);
    archive.mutate(selectedId, {
      onSuccess: (template) =>
        applySaved(template, "Template archived. Deliveries fall back to the built-in default."),
      onError: (cause) => setError(cause instanceof Error ? cause.message : "Archive failed."),
    });
  };

  const onActivate = () => {
    if (!selectedId) {
      return;
    }
    setError(null);
    activate.mutate(selectedId, {
      onSuccess: (template) => applySaved(template, "Template activated."),
      onError: (cause) => setError(cause instanceof Error ? cause.message : "Activation failed."),
    });
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

      <section aria-labelledby="template-list-heading" style={{ marginBottom: 16 }}>
        <h2 id="template-list-heading" style={{ fontSize: 18 }}>
          Existing templates
        </h2>
        {templatesQuery.isLoading ? <p>Loading templates…</p> : null}
        {available.length === 0 ? (
          <p>No tenant templates yet. The built-in EN/TR fallbacks stay active.</p>
        ) : null}
        <ul aria-label="Existing notification templates" style={{ paddingLeft: 20 }}>
          {available.map((template) => (
            <li key={template.id}>
              <button
                type="button"
                onClick={() => chooseTemplate(template)}
                aria-pressed={selectedId === template.id}
              >
                {template.templateKey} · {template.locale} · v{template.version} ·{" "}
                {template.active ? "active" : "archived"}
              </button>
            </li>
          ))}
        </ul>
        <button type="button" onClick={startNewTemplate}>
          New template
        </button>
      </section>

      <div style={{ display: "grid", gap: 8 }}>
        <label htmlFor="template-key">Template key</label>
        <input
          id="template-key"
          value={templateKey}
          disabled={selectedId !== null}
          onChange={(event) => setTemplateKey(event.target.value)}
        />
        <label htmlFor="template-locale">Locale</label>
        <input
          id="template-locale"
          list="template-locale-options"
          value={locale}
          disabled={selectedId !== null}
          onChange={(event) => setLocale(event.target.value)}
        />
        <datalist id="template-locale-options">
          <option value="en" />
          <option value="tr" />
        </datalist>
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
          {selectedId ? (
            <button type="button" onClick={onArchive} aria-label="Archive template">
              Archive
            </button>
          ) : null}
          {selectedId && !selectedActive ? (
            <button type="button" onClick={onActivate} aria-label="Activate template">
              Activate
            </button>
          ) : null}
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
