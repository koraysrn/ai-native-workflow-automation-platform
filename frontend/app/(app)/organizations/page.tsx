"use client";

import { useEffect, useState } from "react";
import { listCompanies, type CompanyDto } from "../../../lib/api";

export default function OrganizationsPage() {
  const [companies, setCompanies] = useState<CompanyDto[]>([]);

  useEffect(() => {
    listCompanies()
      .then(setCompanies)
      .catch(() => {});
  }, []);

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Organizations</h1>
      <ul>
        {companies.map((company) => (
          <li key={company.id}>{company.name}</li>
        ))}
      </ul>
    </div>
  );
}
