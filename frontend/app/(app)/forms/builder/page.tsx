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
}

export default function FormBuilderPage() {
  const [name, setName] = useState("");
  const [fields, setFields] = useState<Field[]>([]);
  const [message, setMessage] = useState("");

  function addField(type: string) {
    setFields((fs) => [...fs, { id: `f${fs.length + 1}`, type, required: false }]);
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
      {message && <p aria-live="polite">{message}</p>}
      <ul>
        {fields.map((field) => (
          <li key={field.id}>
            {field.id} — {field.type} {field.required ? "(required)" : ""}
          </li>
        ))}
      </ul>
    </div>
  );
}
