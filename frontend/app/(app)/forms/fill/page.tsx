"use client";

import { useMemo, useState } from "react";
import FormFileField from "../../../../components/forms/FormFileField";
import { ErrorState, LoadingState } from "../../../../components/ui/QueryState";
import {
  useFormDefinition,
  useFormDefinitions,
  useSubmitForm,
  useWorkflowInstances,
} from "../../../../lib/hooks/resources";

interface SchemaField {
  id: string;
  type: string;
  required?: boolean;
  options?: string[];
}

export default function FillFormPage() {
  const formsQuery = useFormDefinitions();
  const instancesQuery = useWorkflowInstances();
  const forms = formsQuery.data ?? [];
  const instances = instancesQuery.data ?? [];
  const [formId, setFormId] = useState("");
  const [instanceId, setInstanceId] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");

  const formQuery = useFormDefinition(formId);
  const form = formQuery.data;

  const fields = useMemo<SchemaField[]>(() => {
    if (!form?.schemaJson) {
      return [];
    }
    try {
      const parsed = JSON.parse(form.schemaJson) as { fields?: SchemaField[] };
      return parsed.fields ?? [];
    } catch {
      return [];
    }
  }, [form]);

  const submit = useSubmitForm(formId);

  function submitValues() {
    submit.mutate(
      {
        dataJson: JSON.stringify(values),
        workflowInstanceId: instanceId || null,
      },
      {
        onSuccess: (response) => {
          const files = response.files ?? [];
          setMessage(
            files.length === 0
              ? "Submitted"
              : `Submitted with ${files.length} file(s): ${files.map((file) => `${file.name} (${file.scanStatus})`).join(", ")}`,
          );
          setValues({});
        },
        onError: () => setMessage("Submit failed"),
      },
    );
  }

  function setValue(fieldId: string, value: string) {
    setValues((vs) => ({ ...vs, [fieldId]: value }));
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Fill Form</h1>

      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        <select
          aria-label="Form"
          value={formId}
          onChange={(event) => setFormId(event.target.value)}
        >
          <option value="">Select a form</option>
          {forms.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
        <select
          aria-label="Workflow instance"
          value={instanceId}
          onChange={(event) => setInstanceId(event.target.value)}
        >
          <option value="">(optional) Workflow instance</option>
          {instances.map((instance) => (
            <option key={instance.id} value={instance.id}>
              {instance.state} — {instance.id}
            </option>
          ))}
        </select>
      </div>

      {formsQuery.isError && (
        <ErrorState error={formsQuery.error} message="Failed to load forms." />
      )}
      {formQuery.isError && (
        <ErrorState error={formQuery.error} message="Failed to load the selected form." />
      )}
      {formQuery.isFetching && <LoadingState label="Loading form…" />}

      {!formQuery.isFetching && fields.length > 0 && (
        <div style={{ maxWidth: 480 }}>
          {fields.map((field) => (
            <label key={field.id} style={{ display: "block", marginBottom: 12 }}>
              {field.id}
              {field.required ? " *" : ""}
              {field.type === "FILE" ? (
                <FormFileField
                  fieldId={field.id}
                  onUploaded={(fileId) => setValue(field.id, fileId)}
                  onCleared={() => setValue(field.id, "")}
                />
              ) : field.type === "SELECT" ? (
                <select
                  value={values[field.id] ?? ""}
                  onChange={(event) => setValue(field.id, event.target.value)}
                  style={{ display: "block", width: "100%" }}
                >
                  <option value="">Select…</option>
                  {(field.options ?? []).map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              ) : field.type === "MULTI_SELECT" ? (
                <textarea
                  value={values[field.id] ?? ""}
                  onChange={(event) => setValue(field.id, event.target.value)}
                  placeholder="One option per line"
                  style={{ display: "block", width: "100%" }}
                />
              ) : field.type === "BOOLEAN" ? (
                <input
                  type="checkbox"
                  checked={values[field.id] === "true"}
                  onChange={(event) => setValue(field.id, String(event.target.checked))}
                />
              ) : (
                <input
                  type={
                    field.type === "NUMBER"
                      ? "number"
                      : field.type === "EMAIL"
                        ? "email"
                        : field.type === "PHONE"
                          ? "tel"
                          : field.type === "DATE"
                            ? "date"
                            : field.type === "DATETIME"
                              ? "datetime-local"
                              : "text"
                  }
                  value={values[field.id] ?? ""}
                  onChange={(event) => setValue(field.id, event.target.value)}
                  style={{ display: "block", width: "100%" }}
                />
              )}
            </label>
          ))}
          <button type="button" onClick={submitValues} disabled={submit.isPending}>
            {submit.isPending ? "Submitting…" : "Submit"}
          </button>
        </div>
      )}

      {!formQuery.isFetching && formId !== "" && fields.length === 0 && (
        <p>This form has no fields yet.</p>
      )}

      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
