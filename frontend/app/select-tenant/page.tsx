"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { fetchTenants, type TenantMembershipDto } from "../../lib/api";
import { setActiveTenant } from "../../lib/auth";

export default function SelectTenantPage() {
  const router = useRouter();
  const [tenants, setTenants] = useState<TenantMembershipDto[] | null>(null);

  useEffect(() => {
    fetchTenants()
      .then((list) => {
        setTenants(list);
        const single = list[0];
        if (list.length === 1 && single) {
          setActiveTenant(single.tenantId);
          window.location.assign("/dashboard");
        }
      })
      .catch(() => router.replace("/"));
  }, [router]);

  const choose = (tenantId: string) => {
    setActiveTenant(tenantId);
    // Full reload clears all client-side caches, preventing stale cross-tenant data.
    window.location.assign("/dashboard");
  };

  if (tenants === null) {
    return <p>Loading tenants...</p>;
  }

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, fontWeight: 700 }}>Select tenant</h1>
      <div style={{ display: "flex", flexDirection: "column", gap: 8, maxWidth: 360 }}>
        {tenants.map((tenant) => (
          <button
            key={tenant.tenantId}
            type="button"
            onClick={() => choose(tenant.tenantId)}
            style={{
              padding: "10px 12px",
              borderRadius: 8,
              border: "1px solid #cbd5e1",
              background: "#f8fafc",
              cursor: "pointer",
            }}
          >
            {tenant.tenantId} — {tenant.role}
          </button>
        ))}
      </div>
    </div>
  );
}
