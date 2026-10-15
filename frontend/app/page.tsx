"use client";

import { useState, type FormEvent } from "react";
import { login } from "../lib/api";

export default function Home() {
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");

    try {
      const tokens = await login(tenantSlug, email, password);
      localStorage.setItem("awap_access_token", tokens.accessToken);
      localStorage.setItem("awap_refresh_token", tokens.refreshToken);
      setMessage("Login successful");
    } catch {
      setMessage("Login failed");
    }
  }

  return (
    <main style={{ maxWidth: 420, margin: "48px auto", fontFamily: "sans-serif" }}>
      <h1>AWAP</h1>
      <p>AI-Native Workflow Automation Platform</p>

      <form
        onSubmit={handleSubmit}
        style={{ display: "flex", flexDirection: "column", gap: 12 }}
      >
        <input
          placeholder="Tenant slug"
          value={tenantSlug}
          onChange={(event) => setTenantSlug(event.target.value)}
          required
        />
        <input
          placeholder="Email"
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
        <input
          placeholder="Password"
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
        <button type="submit">Sign in</button>
      </form>

      {message && <p aria-live="polite">{message}</p>}
    </main>
  );
}
