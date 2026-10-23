"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  fetchFormDefinition,
  fetchFormDefinitions,
  listWorkflowInstances,
  submitForm,
  type FormDefinitionDetailDto,
} from "../../../../lib/api";

interface SchemaField {
  id: string;
  type: string;
  required?: boolean;
  options?: string[];
}

export default function FillFormPage() {
  const { data: forms = [] } = useQuery({ queryKey: ["forms"], queryFn: fetchFormDefinitions });
  const { data: instances = [] } = useQuery({
    queryKey: ["instances"],
    queryFn: listWorkflowInstances,
  });
  const [formId, setFormId] = useState("");
  const [instanceId, setInstanceId] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");

  const { data: form, isLoading } = useQuery<FormDefinitionDetailDto>({
    queryKey: ["form", formId],
    queryFn: () => fetchFormDefinition(formId),
    enabled: formId !== "",
  });

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

  const submit = useMutation({
    mutationFn: () =>
      submitForm(formId, {
        dataJson: JSON.stringify(values),
        workflowInstanceId: instanceId || null,
      }),
    onSuccess: () => {
      setMessage("Submitted");
      setValues({});
    },
    onError: () => setMessage("Submit failed"),
  });

  function setValue(fieldId: string, value: string) {
    setValues((vs) => ({ ...vs, [fieldId]: value }));
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Fill Form</h1>

      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        <select value={formId} onChange={(event) => setFormId(event.target.value)}>
          <option value="">Select a form</option>
          {forms.map((form) => (
            <option key={form.id} value={form.id}>
              {form.name}
            </option>
          ))}
        </select>
        <select value={instanceId} onChange={(event) => setInstanceId(event.target.value)}>
          <option value="">(optional) Workflow instance</option>
          {instances.map((instance) => (
            <option key={instance.id} value={instance.id}>
              {instance.state} — {instance.id}
            </option>
          ))}
        </select>
      </div>

      {isLoading && <p>Loading form…</p>}

      {!isLoading && fields.length > 0 && (
        <div style={{ maxWidth: 480 }}>
          {fields.map((field) => (
            <label key={field.id} style={{ display: "block", marginBottom: 12 }}>
              {field.id}
              {field.required ? " *" : ""}
              {field.type === "SELECT" ? (
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
          <button type="button" onClick={() => submit.mutate()} disabled={submit.isPending}>
            {submit.isPending ? "Submitting…" : "Submit"}
          </button>
        </div>
      )}

      {!isLoading && formId !== "" && fields.length === 0 && (
        <p>This form has no fields yet.</p>
      )}

      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
