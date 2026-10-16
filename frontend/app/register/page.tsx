"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { registerTenant } from "../../lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const [slug, setSlug] = useState("");
  const [name, setName] = useState("");
  const [adminFullName, setAdminFullName] = useState("");
  const [adminEmail, setAdminEmail] = useState("");
  const [adminPassword, setAdminPassword] = useState("");
  const [message, setMessage] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    try {
      await registerTenant({ slug, name, adminEmail, adminPassword, adminFullName });
      router.push("/");
      router.refresh();
    } catch {
      setMessage("Registration failed");
    }
  }

  return (
    <main style={{ maxWidth: 420, margin: "48px auto", fontFamily: "sans-serif" }}>
      <h1>Create tenant</h1>
      <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <input placeholder="Tenant slug" value={slug} onChange={(e) => setSlug(e.target.value)} required />
        <input placeholder="Company name" value={name} onChange={(e) => setName(e.target.value)} required />
        <input placeholder="Admin full name" value={adminFullName} onChange={(e) => setAdminFullName(e.target.value)} required />
        <input placeholder="Admin email" type="email" value={adminEmail} onChange={(e) => setAdminEmail(e.target.value)} required />
        <input placeholder="Admin password" type="password" value={adminPassword} onChange={(e) => setAdminPassword(e.target.value)} required />
        <button type="submit">Create tenant</button>
      </form>
      {message && <p aria-live="polite">{message}</p>}
    </main>
  );
}
