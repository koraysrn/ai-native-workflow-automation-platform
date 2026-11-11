import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { queryKeys } from "../queryKeys";

function collectFiles(root: string, extensions = [".ts", ".tsx"]): string[] {
  const entries: string[] = [];
  for (const name of readdirSync(root)) {
    const path = join(root, name);
    if (statSync(path).isDirectory()) {
      if (["node_modules", ".next", "__tests__"].includes(name)) {
        continue;
      }
      entries.push(...collectFiles(path, extensions));
    } else if (extensions.some((extension) => name.endsWith(extension))) {
      entries.push(path);
    }
  }
  return entries;
}

describe("query-key factory", () => {
  it("exposes one stable key per resource", () => {
    expect(queryKeys.tasks.list()).toEqual(["tasks"]);
    expect(queryKeys.tasks.mine()).toEqual(["tasks", "mine"]);
    expect(queryKeys.workflowDefinitions.list()).toEqual(["workflow-definitions"]);
    expect(queryKeys.workflowInstances.list()).toEqual(["instances"]);
    expect(queryKeys.documents.list()).toEqual(["documents"]);
    expect(queryKeys.forms.list()).toEqual(["forms"]);
    expect(queryKeys.forms.detail("form-1")).toEqual(["forms", "detail", "form-1"]);
    expect(queryKeys.analytics.summary()).toEqual(["analytics-summary", null, null]);
    expect(queryKeys.analytics.summary("2026-01-01", "2026-01-31")).toEqual([
      "analytics-summary",
      "2026-01-01",
      "2026-01-31",
    ]);
    expect(queryKeys.organizationTree.all).toEqual(["organization-tree"]);
    expect(queryKeys.notifications.list(false)).toEqual(["notifications", false]);
    expect(queryKeys.notifications.list(true)).toEqual(["notifications", true]);
    expect(queryKeys.notifications.unreadCount()).toEqual(["notifications", "unread-count"]);
    expect(queryKeys.notificationTemplates.list("TASK_ASSIGNED")).toEqual([
      "notification-templates",
      "TASK_ASSIGNED",
    ]);
    expect(queryKeys.tenantMemberships.all).toEqual(["tenant-memberships"]);
    expect(queryKeys.health.status()).toEqual(["health", "status"]);
  });

  it("keeps the resource root as the prefix so broad invalidation reaches every variant", () => {
    // Mutations invalidate the `all` key; TanStack matches by prefix, so filtered variants
    // (unread-only inbox, one template key) are refreshed too.
    expect(queryKeys.notifications.list(true).slice(0, 1)).toEqual([
      ...queryKeys.notifications.all,
    ]);
    expect(queryKeys.notificationTemplates.list("x").slice(0, 1)).toEqual([
      ...queryKeys.notificationTemplates.all,
    ]);
    expect(queryKeys.forms.detail("id").slice(0, 1)).toEqual([...queryKeys.forms.all]);
  });

  it("produces distinct keys for distinct resources", () => {
    const serialised = [
      queryKeys.tasks.list(),
      queryKeys.workflowDefinitions.list(),
      queryKeys.workflowInstances.list(),
      queryKeys.documents.list(),
      queryKeys.forms.list(),
      queryKeys.organizationTree.all,
      queryKeys.notifications.list(false),
      queryKeys.notificationTemplates.list(),
      queryKeys.tenantMemberships.all,
      queryKeys.health.status(),
    ].map((key) => JSON.stringify(key));
    expect(new Set(serialised).size).toBe(serialised.length);
  });
});

describe("server state guard", () => {
  it("keeps useQuery/useMutation inside lib/hooks so every page goes through a hook", () => {
    const roots = ["app", "components"].map((directory) => join(process.cwd(), directory));
    const offenders = roots
      .flatMap((root) => collectFiles(root))
      .filter((file) => {
        const source = readFileSync(file, "utf8");
        return (
          /\buse(Query|Mutation)\s*\(/.test(source) && !file.includes(`${join("lib", "hooks")}`)
        );
      })
      .map((file) => file.replace(`${process.cwd()}/`, ""));
    expect(offenders).toEqual([]);
  });

  it("keeps direct API fetches out of pages and components", () => {
    const offenders = ["app", "components"]
      .flatMap((directory) => collectFiles(join(process.cwd(), directory)))
      .filter((file) => {
        const source = readFileSync(file, "utf8");
        // Next.js route handlers legitimately forward a CSP report; everything else must use lib/api.
        if (file.endsWith(join("app", "api", "csp-report", "route.ts"))) {
          return false;
        }
        return /\bfetch\s*\(/.test(source);
      })
      .map((file) => file.replace(`${process.cwd()}/`, ""));
    expect(offenders).toEqual([]);
  });
});
