# AI node and SCRIPT sandbox — security design

Issue #132 enables the `AI` workflow node and adds a `SCRIPT` node. Both are **fail-closed**: they
return `501 Not Implemented` unless the deployment explicitly enables them
(`AI_NODE_ENABLED=true`, `SCRIPT_NODE_ENABLED=true`).

## AI node

| Control | Implementation |
|---|---|
| Provider abstraction | `AiBackendClient` seam + `AiAgentService` (timeout, validation); `LocalHeuristicAiBackend` is the default, `OpenAiCompatibleAiBackend` (`AI_PROVIDER=openai`) calls any OpenAI-compatible `/chat/completions` endpoint with a connect/read timeout and falls back to the local provider on 429/5xx/transport/empty answers |
| Prompt sanitization / guardrails | `AiGuardrails` strips NUL characters, caps the prompt at 8 000 chars and blocks `[INST]`, `<\|`, "ignore previous instructions", "system prompt", "reveal your instructions", "you are now", "jailbreak", "developer mode" and role-spoofing markers |
| Timeout | `AiAgentService` runs the provider call on a bounded executor with `awap.ai.timeout-ms` (default 5 000 ms) |
| Confidence + HITL | `AiConfidenceEstimator` (deterministic, explainable) → below `awap.ai.confidence-threshold` (0.7) the node transitions to `WAITING` with `AI_HITL_REQUIRED` instead of advancing |
| Budget / cost | `AiBudgetGuard` reserves estimated tokens per tenant (`awap.ai.monthly-token-budget`, default 200 000) and refuses with `429`; cost is recorded at `awap.ai.cost-micros-per-token` |
| Rate/caching | Per-instance LRU cache keyed by the SHA-256 of action+prompt (`awap.ai.requests{outcome="cache_hit"}`); repeated prompts never hit the provider |
| Fallback | The provider seam allows a fallback chain; a provider failure surfaces as `502`/`504` and the node fails the instance rather than silently continuing |
| Audit | Every decision (success, blocked, budget, cache hit, HITL) writes an immutable `automation_audit_records` row with prompt hash, confidence, tokens and cost |

## SCRIPT node

| Control | Implementation |
|---|---|
| Sandbox choice | In-process **expression sandbox** (`ScriptSandbox`): scripts are parsed into a tiny grammar instead of executing JVM bytecode, so filesystem, network, reflection, process execution and imports are not expressible at all |
| Allowed syntax | numbers, strings, booleans, `ctx.field` reads, `+ - * / %`, comparisons, `and`/`or`/`not`, and the whitelisted functions `len`, `upper`, `lower`, `coalesce`, `abs` |
| Denied by construction | method calls (`ctx.x.getClass()`), unknown identifiers (`env`, `PATH`, `System`, `Runtime`), imports, any character outside the grammar |
| Timeout | mandatory `awap.script.timeout-ms` (default 10 000 ms) enforced through a virtual-thread future; timeout → `408` + `TIMEOUT` audit |
| Size limits | script ≤ 4 000 chars, context ≤ 32 KiB (`awap.script.max-context-chars`), output ≤ 8 192 chars (`awap.script.max-output-chars`) |
| Secrets | the script only receives the node's `ctx` JSON; environment variables and Spring beans are unreachable |
| Feature flag | `SCRIPT_NODE_ENABLED=false` by default; the node fails closed when the sandbox bean is absent |
| Audit | every execution writes an immutable `SCRIPT` audit row (script hash, outcome, error detail) |

## Audit storage

`V48__automation_audit.sql` creates `automation_audit_records` with RLS, a check constraint on the
kind/outcome and `REVOKE UPDATE, DELETE` for the runtime role, so an audited AI decision or script run
cannot be rewritten after the fact. Read it per tenant/instance via
`AutomationAuditRepository.findByTenantIdAndInstanceIdOrderByCreatedAtAsc`.

## Metrics

| Metric | Tags | Use |
|---|---|---|
| `awap.ai.requests` | `outcome` (`success`/`hitl`/`blocked`/`budget_exceeded`/`cache_hit`) | Guardrail blocks and budget pressure |
| `awap.ai.tokens` / `awap.ai.cost.micros` | – | Spend tracking |
| `awap.script.executions` | `outcome` (`success`/`failed`/`timeout`) | Sandbox health |

## Threat-model notes

- **Prompt injection** — blocked before the provider; a bypass still cannot exceed the tenant's
  authority because tool calls require the caller's security context (AI rules in `CLAUDE.md`).
- **SCRIPT RCE** — the grammar cannot express host access; the residual risk is a parser bug, which
  is why the sandbox stays disabled by default and every execution is audited.
- **Resource exhaustion** — timeout + size caps; the executor is virtual-thread based, so a slow
  script cannot starve the workflow thread pool.
- **Secret exposure** — the context is an explicit JSON object; nothing else is in scope.
- **Supply chain** — there are no script imports/libraries to whitelist because the grammar forbids
  them; adding one would require a new ADR.

## Deferred

A full worker-container/WASM sandbox (for scripts needing loops or libraries) is intentionally out
of scope; the expression sandbox covers the documented use case and keeps the attack surface at a
parser. This is recorded in the PR and should be revisited with a new ADR before adding richer
scripting.
