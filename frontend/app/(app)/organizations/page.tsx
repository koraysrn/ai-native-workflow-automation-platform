"use client";

import OrgTreeNode from "../../../components/organizations/OrgTreeNode";
import { useOrganizationTree } from "../../../lib/hooks/useOrganizationTree";

export default function OrganizationsPage() {
  const { data: companies = [], isLoading, isError } = useOrganizationTree();

  if (isLoading) {
    return <p>Loading organization tree…</p>;
  }
  if (isError) {
    return <p>Failed to load organization tree.</p>;
  }

  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Organizations</h1>
      {companies.length === 0 ? (
        <p>No companies yet.</p>
      ) : (
        <OrgTreeNode companies={companies} />
      )}
    </div>
  );
}
