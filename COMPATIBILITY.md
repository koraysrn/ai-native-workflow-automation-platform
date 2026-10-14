# Compatibility Matrix

This matrix records the fixed versions and the results of the pilot
compatibility verification performed before development started. It is updated
on every version change (see `CLAUDE.md` — Version Compatibility Rules).

## Fixed Versions

| Component | Fixed Version | Verified | Notes |
|---|---|---|---|
| Java | 25 (LTS, Temurin) | ✅ | `mvn verify` BUILD SUCCESS with Spring Boot 4.1.1 |
| Spring Boot | 4.1.1 | ✅ | Parent BOM; `--release 25` compile |
| Maven | 3.9.x | ✅ | 3.9.9 used for the pilot test; Maven Wrapper committed |
| TypeScript | 6.x | ✅ | Chosen for ESLint compatibility; upgrade to 7.0.2 when `typescript-eslint` supports it |
| Next.js | 16.4.x | ✅ | `next build` (Turbopack) OK |
| React | 19.3.x | ✅ | |
| Node.js | 24 LTS | ✅ | |
| pnpm | 12.x | ✅ | |
| Python | 3.14 (conda) | ✅ | Chosen for ecosystem maturity; 3.15 when `cp315` wheels land |
| FastAPI | 0.135.x | ✅ | conda-forge |
| LangChain | 1.4.x | ✅ | installed via `pip` inside the conda env |
| LangGraph | 1.2.x | ✅ | installed via `pip` inside the conda env |

## AI Layer Notes

- The AI Layer environment is managed **only with conda**; `environment.yml`
  is the single source of truth.
- `python=3.14`, `fastapi`, and `ruff` come from conda-forge; `langchain` and
  `langgraph` are installed from PyPI via the `pip` section so that the latest
  1.x majors are available.
- Python 3.15.0 works with conda, but conda-forge currently ships older
  LangChain/LangGraph majors (0.3.x / 0.4.x) for it. To keep the AI stack
  current, Python 3.14 is used until conda-forge publishes 1.x for Python 3.15
  or `ormsgpack`/`uuid-utils` ship `cp315` wheels on PyPI.

## Frontend Notes

- TypeScript 6.x is used so that ESLint (`typescript-eslint`) works end to end.
- TypeScript 7.0.2 (native compiler) passes `tsc --noEmit` and `next build`,
  but `typescript-eslint` does not support TS 7.0 yet
  (typescript-eslint#10940).
- When `typescript-eslint` ships TS 7 support, the project will upgrade to 7.0.2.

## Version Upgrade Policy

- Upgrades are performed only through Renovate / Dependabot after CI passes.
- On every upgrade: OWASP Dependency Scan, `mvn verify`,
  `pnpm build && pnpm typecheck && pnpm lint`, `ruff check`, and the conda
  environment solve must pass.
- A PR containing an incompatible version is automatically rejected in CI.
