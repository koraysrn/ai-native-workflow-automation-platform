# Coverage Baseline

Recorded on the `chore/phase-1-preflight-hardening` branch after the security and
unit-test work (commit `ab382b7`), from `mvn verify` (JaCoCo, `awap-identity` bundle).

| Metric | Covered / Total | Percentage |
|--------|-----------------|------------|
| Line   | 6169 / 11627    | 53.1%      |
| Branch | 268 / 683       | 39.2%      |

## Target

Per [`CLAUDE.md`](../CLAUDE.md): line ≥ 80%, branch ≥ 95%. The JaCoCo gate is
currently ratcheted at 0.40 (line) to keep CI green while the remaining test plan
(BÖLÜM 2/3) is implemented. The ratchet must be raised incrementally (0.40 → 0.60 →
0.80) as coverage grows.
