import { useQuery } from "@tanstack/react-query";
import { fetchOrganizationTree } from "../api";

export function useOrganizationTree() {
  return useQuery({
    queryKey: ["organization-tree"],
    queryFn: fetchOrganizationTree,
  });
}
