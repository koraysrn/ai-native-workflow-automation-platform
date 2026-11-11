/**
 * Organization tree query. The hook lives in `resources.ts` with every other server-state hook so
 * the query-key factory has a single consumer contract; this module keeps the import path stable.
 */
export { useOrganizationTree } from "./resources";
