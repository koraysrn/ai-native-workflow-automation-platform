"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

const navItems = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/workflows", label: "Workflows" },
  { href: "/tasks", label: "Tasks" },
  { href: "/instances", label: "Instances" },
];

export default function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      <aside
        style={{
          width: 220,
          background: "#0f172a",
          color: "#e2e8f0",
          padding: 16,
        }}
      >
        <h2 style={{ fontSize: 18, fontWeight: 700, margin: "0 0 16px" }}>AWAP</h2>
        <nav style={{ display: "flex", flexDirection: "column", gap: 8 }}>
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
      </aside>
      <main style={{ flex: 1, padding: 24 }}>{children}</main>
    </div>
  );
}
