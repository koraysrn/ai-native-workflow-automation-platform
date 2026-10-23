"use client";

import { useState } from "react";
import { createFormDefinition } from "../../../../lib/api";

const fieldTypes = [
  "TEXT",
  "TEXTAREA",
  "NUMBER",
  "EMAIL",
  "PHONE",
  "DATE",
  "DATETIME",
  "BOOLEAN",
  "SELECT",
  "MULTI_SELECT",
  "FILE",
];

interface Field {
  id: string;
  type: string;
  required: boolean;
  minLength?: number | undefined;
  maxLength?: number | undefined;
  pattern?: string | undefined;
  min?: number | undefined;
  max?: number | undefined;
  options?: string[] | undefined;
}

export default function FormBuilderPage() {
  const [name, setName] = useState("");
  const [fields, setFields] = useState<Field[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  const selected = fields.find((field) => field.id === selectedId) ?? null;

  function addField(type: string) {
    const field: Field = { id: `f${fields.length + 1}`, type, required: false };
    setFields((fs) => [...fs, field]);
    setSelectedId(field.id);
  }

  function updateField(patch: Partial<Field>) {
    setFields((fs) => fs.map((field) => (field.id === selectedId ? { ...field, ...patch } : field)));
  }

  async function save() {
    setMessage("");
    try {
      await createFormDefinition(name, JSON.stringify({ fields }));
      setMessage("Saved");
    } catch {
      setMessage("Save failed");
    }
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Form Builder</h1>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 12 }}>
        {fieldTypes.map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => addField(type)}
            style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #cbd5e1" }}
          >
            {type}
          </button>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        <input
          placeholder="Form name"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <button type="button" onClick={save}>
          Save
        </button>
      </div>

      <div style={{ display: "flex", gap: 16 }}>
        <ul style={{ width: 240, padding: 0, listStyle: "none" }}>
          {fields.map((field) => (
            <li key={field.id} style={{ marginBottom: 4 }}>
              <button
                type="button"
                onClick={() => setSelectedId(field.id)}
                style={{ width: "100%", textAlign: "left", padding: "6px 8px" }}
              >
                {field.id} — {field.type} {field.required ? "(required)" : ""}
              </button>
            </li>
          ))}
        </ul>

        {selected && (
          <div style={{ flex: 1, border: "1px solid #e2e8f0", borderRadius: 8, padding: 12 }}>
            <h2 style={{ fontSize: 16, fontWeight: 700 }}>{selected.type} field</h2>
            <label style={{ display: "block", marginBottom: 8 }}>
              <input
                type="checkbox"
                checked={selected.required}
                onChange={(event) => updateField({ required: event.target.checked })}
              />{" "}
              Required
            </label>
            {(selected.type === "TEXT" || selected.type === "TEXTAREA") && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Min length
                  <input
                    type="number"
                    value={selected.minLength ?? ""}
                    onChange={(event) =>
                      updateField({ minLength: event.target.value ? Number(event.target.value) : undefined })
                    }
                    style={{ display: "block", width: "100%" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Max length
                  <input
                    type="number"
                    value={selected.maxLength ?? ""}
                    onChange={(event) =>
                      updateField({ maxLength: event.target.value ? Number(event.target.value) : undefined })
                    }
                    style={{ display: "block", width: "100%" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Pattern (regex)
                  <input
                    value={selected.pattern ?? ""}
                    onChange={(event) => updateField({ pattern: event.target.value || undefined })}
                    style={{ display: "block", width: "100%" }}
                  />
                </label>
              </>
            )}
            {selected.type === "NUMBER" && (
              <>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Min
                  <input
                    type="number"
                    value={selected.min ?? ""}
                    onChange={(event) =>
                      updateField({ min: event.target.value ? Number(event.target.value) : undefined })
                    }
                    style={{ display: "block", width: "100%" }}
                  />
                </label>
                <label style={{ display: "block", marginBottom: 8 }}>
                  Max
                  <input
                    type="number"
                    value={selected.max ?? ""}
                    onChange={(event) =>
                      updateField({ max: event.target.value ? Number(event.target.value) : undefined })
                    }
                    style={{ display: "block", width: "100%" }}
                  />
                </label>
              </>
            )}
            {(selected.type === "SELECT" || selected.type === "MULTI_SELECT") && (
              <label style={{ display: "block", marginBottom: 8 }}>
                Options (one per line)
                <textarea
                  value={(selected.options ?? []).join("\n")}
                  onChange={(event) =>
                    updateField({
                      options: event.target.value
                        .split("\n")
                        .map((line) => line.trim())
                        .filter(Boolean),
                    })
                  }
                  style={{ display: "block", width: "100%", minHeight: 80 }}
                />
              </label>
            )}
          </div>
        )}
      </div>

      {message && <p aria-live="polite">{message}</p>}
    </div>
  );
}
