# Accessibility coverage

Issue #184 expands accessibility from a single home-page scan to every
user-facing screen. The suite lives in
[`frontend/e2e/accessibility.spec.ts`](../frontend/e2e/accessibility.spec.ts) and
uses the shared fixtures in
[`frontend/e2e/a11yFixtures.ts`](../frontend/e2e/a11yFixtures.ts).

## Screen and state matrix

| Screen | Route | Empty | Populated | Notes |
|---|---|:---:|:---:|---|
| Login | `/` | ✅ | — | Form labels and error live region |
| Register | `/register` | ✅ | — | Form labels |
| Tenant selection | `/select-tenant` | — | ✅ | Two memberships |
| Dashboard | `/dashboard` | ✅ | ✅ | Tasks/instances counts |
| Tasks | `/tasks` | ✅ | ✅ | Populated state opens the task detail panel |
| Workflows | `/workflows` | ✅ | ✅ | Populated state opens the start dialog |
| Workflow designer | `/workflows/designer` | ✅ | ✅ | Populated state adds a TASK node |
| Form builder | `/forms/builder` | ✅ | ✅ | Populated state adds a TEXT field inspector |
| Form fill | `/forms/fill` | ✅ | ✅ | Populated state selects the form and renders its fields |
| Documents | `/documents` | ✅ | ✅ | Live scan-status region |
| Notifications | `/notifications` | ✅ | ✅ | Unread live region |
| Notification templates | `/notification-templates` | ✅ | ✅ | List + editor |
| Analytics | `/analytics` | ✅ | ✅ | Empty and aggregate tables |
| Workflow instances | `/instances` | ✅ | ✅ | State labels |
| Organizations | `/organizations` | ✅ | ✅ | Empty and nested tree |

Keyboard focus is exercised on every screen. Form-label, live-region and
error-announcement checks run for login, register, documents and notifications.

## Gate

- axe tags: `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`.
- A `critical` or `serious` violation fails the Playwright test.
- Color contrast is covered by the `color-contrast` rule in the same tags.
- Keyboard tests require the first real control to receive focus and show an
  outline/box-shadow focus indicator. Next.js dev-only `NEXTJS-PORTAL` elements
  are skipped because they do not exist in production.
- Every scan is written to `frontend/a11y-report/report.json`; CI uploads it as
  the `a11y-report` artifact even when a screen fails.

## Local commands

```bash
cd frontend
pnpm test:a11y
```

The suite uses deterministic API mocks, so it does not need Postgres, Kafka or a
backend. Animations are reduced with `prefers-reduced-motion`; no inline style is
injected because the CSP would block it.

## Unhappy paths

The suite is expected to fail when:

- a form control loses its accessible name (login/register/task detail/file
  input/form selects);
- a low-contrast control is introduced;
- a status change loses its `aria-live` region;
- a screen cannot be seeded (the fixture fails instead of skipping).

A focus trap is only meaningful for a modal. The current UI uses inline detail
panels and dialogs without a modal overlay; when a real modal is introduced, add
a keyboard trap test to this suite.
