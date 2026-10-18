// Seeds N tenants with an admin user via the registration endpoint and writes
// the tenant list to load-test/tenants.json for the k6 scenario to consume.
// Usage: node load-test/seed.mjs [count]
const base = process.env.API_BASE ?? "http://localhost:8080";
const count = Number.parseInt(process.argv[2] ?? "1000", 10);

const tenants = [];
for (let i = 0; i < count; i += 1) {
  const slug = `tenant-${i}-${Date.now()}`;
  const payload = {
    slug,
    name: `Tenant ${i}`,
    adminEmail: `admin-${i}-${Date.now()}@example.com`,
    adminPassword: "Password123!",
    adminFullName: `Admin ${i}`,
  };
  const response = await fetch(`${base}/api/v1/tenants`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error(`Seeding tenant ${i} failed with status ${response.status}`);
  }
  tenants.push({ slug, email: payload.adminEmail, password: payload.adminPassword });
}

await import("node:fs/promises").then((fs) =>
  fs.writeFile(
    new URL("./tenants.json", import.meta.url),
    JSON.stringify(tenants, null, 2),
    "utf8",
  ),
);

console.log(`Seeded ${tenants.length} tenants.`);
