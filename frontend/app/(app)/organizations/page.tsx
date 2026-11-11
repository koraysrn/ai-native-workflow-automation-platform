"use client";

import OrgTreeNode from "../../../components/organizations/OrgTreeNode";
import { EmptyState, ErrorState, LoadingState } from "../../../components/ui/QueryState";
import { useOrganizationTree } from "../../../lib/hooks/useOrganizationTree";

export default function OrganizationsPage() {
  const companies = useOrganizationTree();

  if (companies.isLoading) {
    return <LoadingState label="Loading organization tree…" />;
  }
  if (companies.isError) {
    return <ErrorState error={companies.error} message="Failed to load organization tree." />;
  }

  const tree = companies.data ?? [];
  return (
    <div>
      <h1 style={{ fontSize: 24, fontWeight: 700 }}>Organizations</h1>
      {tree.length === 0 ? (
        <EmptyState message="No companies yet." />
      ) : (
        <OrgTreeNode companies={tree} />
      )}
    </div>
  );
}
