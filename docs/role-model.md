# Role model and position-based assignment

Issue #194 introduced explicit role assignment for organization positions. This document
describes the role model, the authority rules, how a position role propagates to identity
accounts, and the audit/outbox contract.

## Supported roles

Roles are defined by [`Role`](../backend/identity/src/main/java/com/awap/identity/domain/Role.java)
and ordered from the highest to the lowest authority. The enum declaration order **is** the
authority order: a caller may assign its own role or any constant declared after it.

| Rank | Role           | Key permissions                                                                 |
| ---- | -------------- | ------------------------------------------------------------------------------- |
| 1    | `SYSTEM_ADMIN` | All tenant, user, workflow, instance, task, form and file permissions           |
| 2    | `TENANT_ADMIN` | Tenant user/workflow management, task approval, forms and files                 |
| 3    | `MANAGER`      | Workflow creation, instance read, task approval and task management             |
| 4    | `OPERATIONS`   | Instance read, task management, form management and file upload                 |
| 5    | `EMPLOYEE`     | Instance read, task management and form management (safe default)               |

Permissions are granted by the role itself; there is no per-user permission override.

## Authority rules

- A position always carries a non-null role. Domain construction without a role and the database
  column both default to `EMPLOYEE`.
- A caller can create or change a position role only when the target role is at or below the
  caller's role (`Role.canAssign`). Attempting to assign a higher role returns `403 Forbidden`.
- An unknown caller role is rejected with `403 Forbidden`; an unknown target role in a request body
  is rejected with `400 Bad Request` (RFC 7807 problem details).
- A position identifier from another tenant is never returned: lookups are always scoped by
  `tenant_id` and a foreign identifier produces `404 Not Found`.

The API requires the `USER_MANAGE` authority for all position endpoints.

## Propagation to identity accounts

Every organization member is linked to an identity account by email. When a position role changes:

1. The position row is updated inside a transaction.
2. Every `organization_users` row with that `position_id` is resolved and the matching identity
   user (`users.role`) receives the new role.
3. The access token and refresh token minted on the next login/refresh read the new role from
   `users.role`, so the effective authorities change without invalidating existing sessions.

Creating an organization user with a position applies the position role immediately to an existing
identity account; members without an identity account are left untouched until the account exists.

## Audit and events

Each role change writes:

- an immutable `organization_audit_events` row (tenant, entity type `POSITION`, entity id, action
  `ROLE_CHANGED`, old value, new value, actor id, timestamp);
- an outbox event `organization.position.role_changed` whose payload contains `tenantId`,
  `entityId`, `oldRole`, `newRole` and the `affectedUserIds` list. The outbox row inherits the
  current correlation ID through the shared outbox publisher.

Setting the same role twice is a deterministic no-op: no audit row and no outbox event are written.

## Concurrency

`positions.version` is a JPA `@Version` column (migration
[`V52__position_role_assignment.sql`](../backend/identity/src/main/resources/db/migration/V52__position_role_assignment.sql)).
Two concurrent role updates are serialized by the optimistic-lock predicate: the stale writer is
rejected instead of overwriting the newer role, and clients can retry against the fresh version.

## Database migration and backfill

`V20` added the `positions.role` column with the safe `EMPLOYEE` default. `V52` keeps that default
for existing rows, adds the `version` column defaulting to `0`, constrains the persisted values with
`chk_positions_role`, and creates the tenant-isolated audit table (RLS enabled and forced, grants
limited to `awap_app`).

## API surface

| Method | Path                                         | Purpose                                  |
| ------ | -------------------------------------------- | ---------------------------------------- |
| `POST` | `/api/v1/organizations/positions`            | Create a position with a validated role  |
| `PUT`  | `/api/v1/organizations/positions/{id}/role`  | Change a position role and propagate it  |
| `GET`  | `/api/v1/organizations/positions`            | List positions with their roles          |

The OpenAPI contract is in
[`docs/openapi/awap-openapi.yaml`](openapi/awap-openapi.yaml) and is checked for controller
coverage and breaking changes in CI.
