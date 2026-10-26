"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { getActiveTenant, logout } from "../../lib/auth";

const navItems = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/workflows", label: "Workflows" },
  { href: "/workflows/designer", label: "Designer" },
  { href: "/tasks", label: "Tasks" },
  { href: "/instances", label: "Instances" },
  { href: "/organizations", label: "Organizations" },
  { href: "/forms/builder", label: "Forms" },
  { href: "/forms/fill", label: "Fill Form" },
  { href: "/documents", label: "Documents" },
  { href: "/analytics", label: "Analytics" },
];

export default function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      <aside
        style={{
          width: 220,
          background: "#0f172a",
          color: "#e2e8f0",
          padding: 16,
          display: "flex",
          flexDirection: "column",
        }}
      >
        <h2 style={{ fontSize: 18, fontWeight: 700, margin: "0 0 16px" }}>AWAP</h2>
        <nav style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1 }}>
          {navItems.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              style={{
                color: pathname === item.href ? "#818cf8" : "#cbd5e1",
                textDecoration: "none",
                padding: "6px 8px",
                borderRadius: 6,
              }}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <button
          type="button"
          onClick={() => router.push("/select-tenant")}
          style={{
            background: "#1e293b",
            color: "#e2e8f0",
            border: "none",
            padding: "8px 10px",
            borderRadius: 6,
            cursor: "pointer",
            marginBottom: 8,
          }}
        >
          Tenant: {getActiveTenant() ?? "select"}
        </button>
        <button
          type="button"
          onClick={() => logout()}
          style={{
            background: "#334155",
            color: "#e2e8f0",
            border: "none",
            padding: "8px 10px",
            borderRadius: 6,
            cursor: "pointer",
          }}
        >
          Logout
        </button>
      </aside>
      <main style={{ flex: 1, padding: 24 }}>{children}</main>
    </div>
  );
}
