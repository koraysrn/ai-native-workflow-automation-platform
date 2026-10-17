# AI Layer Compatibility

Pilot compatibility report for the AI layer on Python 3.15.0.

## Result

✅ All C-based dependencies compile/import and run a smoke assertion on Python 3.15.0.

| Dependency | Kind | Verified |
|---|---|---|
| numpy | C-based | ✅ matrix multiplication |
| orjson | C-based | ✅ JSON round-trip |
| pydantic-core | C-based | ✅ `SchemaValidator` available |
| fastapi | pure | ✅ via `uv sync --locked` |
| uvicorn | pure | ✅ via `uv sync --locked` |
| langchain | pure | ✅ 1.4.5 |
| langgraph | pure | ✅ 1.2.15 |

## How to reproduce

```bash
uv sync --locked
uv run python compat_check.py
```

## Notes

- Python is pinned to 3.15.0 via `ai-layer/.python-version` and `requires-python`.
- `uv.lock` is committed and CI enforces `uv sync --locked`.
- `environment.yml` (conda) was removed; the layer is now uv-managed.
