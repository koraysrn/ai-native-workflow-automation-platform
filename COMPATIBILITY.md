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
| TypeScript | 7.0.2 | ✅ | Native compiler; `typecheck`/`build` on TS 7, `lint` via TS 6 API |
| Next.js | 16.4.x | ✅ | `next build` (Turbopack) OK |
| React | 19.3.x | ✅ | |
| Node.js | 24 LTS | ✅ | |
| pnpm | 12.x | ✅ | |
| Python | 3.15.0 (uv) | ✅ | uv-managed with committed `uv.lock`; cp315 wheels verified |
| FastAPI | 0.135.x | ✅ | installed via `uv` from PyPI |
| LangChain | 1.4.x | ✅ | installed via `uv` from PyPI |
| LangGraph | 1.2.x | ✅ | installed via `uv` from PyPI |

## AI Layer Notes

- The AI Layer environment is managed **with uv**; `ai-layer/pyproject.toml`
  and the committed `ai-layer/uv.lock` are the single source of truth.
- Python is pinned to 3.15.0 via `[tool.uv]`; C-based dependencies
  (`numpy`, `orjson`, `pydantic-core`) are verified by the pilot compatibility
  check (`ai-layer/compat_check.py`).
- `langchain` (1.4.x) and `langgraph` (1.2.x) are installed from PyPI.
- `environment.yml` was removed; the AI layer no longer depends on conda.

## Frontend Notes

- TypeScript 7.0.2 (native compiler) is pinned in `frontend/package.json`.
- `tsconfig.json` enables `strict`, `noUncheckedIndexedAccess` and
  `exactOptionalPropertyTypes`.
- `pnpm typecheck` (`next typegen && tsc --noEmit`) and `pnpm build` run on
  TypeScript 7.0.2.
- `pnpm lint` runs ESLint via `scripts/lint.mjs`, which redirects
  typescript-eslint to the `typescript-6` alias (TypeScript 6 API) until
  typescript-eslint ships TS 7 support (typescript-eslint#10940).

## Version Upgrade Policy

- Upgrades are performed only through Renovate / Dependabot after CI passes.
- On every upgrade: OWASP Dependency Scan, `mvn verify`,
  `pnpm build && pnpm typecheck && pnpm lint`, `ruff check`, and
  `uv sync --locked` must pass.
- A PR containing an incompatible version is automatically rejected in CI.
