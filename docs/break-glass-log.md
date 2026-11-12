# Break-glass log — incident #257

Incident [#257](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/257)
authorised the single-maintainer break-glass merges for the sequential issues #197–#205.
This page is the committed audit trail required by runbook
[O22](operations-runbook.md#o22--branch-protection-drift-and-break-glass-issue-178).
The machine-readable source is [`settings/break-glass-log.json`](../settings/break-glass-log.json);
the tests fail when this table and the JSON diverge.

## Incident scope

| Field | Value |
|-------|-------|
| Incident | #257 |
| Owner approving the exception | `@koraysrn` |
| Reason | One maintainer; GitHub never counts a self-approval against `required_approving_review_count=1` |
| Expiry | `2026-11-13T23:59:59Z` |
| Scope | Only the approval count is lowered to 0 for one merge at a time |
| Unchanged | `enforce_admins=true`, required status checks, force-push/deletion blocks, drift workflow |
| Restore | `scripts/apply-branch-protection.sh --apply` then `--verify` immediately after every merge |

## Merged PRs

Every row required all required status checks to be green **before** the approval count was
lowered. Restore was executed immediately after the merge.

| Issue | PR | Merge commit | Merged at (UTC) | Checks green | Restore `--apply` | Restore `--verify` |
|-------|----|--------------|-----------------|--------------|-------------------|--------------------|
| — (CI fix) | #258 | `8e5b408a415a8f4b56aead84f9de471834930c92` | 2026-11-11T15:47:36Z | yes | updated | matches |
| #197 | #256 | `d8560f5dc8dc4d7a3fa8b87f91086b0e12f3846c` | 2026-11-11T16:35:03Z | yes | updated | matches |
| #198 | #260 | `3163d070ceebcf67041a1d8567da40430857cf1d` | 2026-11-11T17:11:53Z | yes | updated | matches |
| #199 | #261 | `556c1a3be1e508ce0ed92c68dafe5aaf38f0e682` | 2026-11-11T17:51:32Z | yes | updated | matches |
| #200 | #262 | `0a5eb19fd8e19737d547bcb7ed52a5436bc793f6` | 2026-11-11T18:42:53Z | yes | updated | matches |
| #201 | #263 | `d88971efc57e060f723a6d45461103ac4b1a1116` | 2026-11-11T22:15:20Z | yes | updated | matches |
| #202 | #264 | `2c5c05a40b353f7f346cc7666ab8c342255cc7ce` | 2026-11-11T23:26:42Z | yes | updated | matches |
| #203 | #265 | `bc01efb60fbb2eaaaa1164403d367ba116831fc4` | 2026-11-12T12:39:57Z | yes | updated | matches |
| #204 | #266 | `f21823c96b7bb01df5674d7a4c7e942fc899a80d` | 2026-11-12T13:56:07Z | yes | updated | matches |
| #205 | #267 | `aeed9e9a374f20174e114bce04d6901eaf9899fc` | 2026-11-12T14:29:32Z | yes | updated | matches |

PR #258 (CI image build fix) carried no issue of its own but was part of the same sequential
pipeline; it is recorded here for completeness.

## Post-incident proof

| Check | Value |
|-------|-------|
| Live approval count | `1` |
| `enforce_admins` | `true` |
| Drift failure observed during the exception | none — every exception window was shorter than the drift schedule |
| Last drift run inside the incident | run `43235325197` (green, `f21823c`) |
| Final drift run (green) | run `43240412231` (green, `aeed9e9`) |
| Green `main` CI run | run `43237731691` (green, `aeed9e9`) |
| Green `main` CI commit | `aeed9e9a374f20174e114bce04d6901eaf9899fc` |

The previous incident [#243](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/243)
covered issues #189–#196 and remains closed; its record is superseded by this one in the JSON log.

## Closure rule

Incident #257 is closed by the governance PR that introduced this log. It cannot authorise any
further merge. Every future single-maintainer break-glass must:

1. open a **new** incident with its own reason, scope, owner and expiry;
2. append its merges to [`settings/break-glass-log.json`](../settings/break-glass-log.json) and this
   table in the same PR;
3. restore protection with `--apply` and `--verify` immediately after each merge; and
4. attach the restore output, the final drift run and the green `main` CI run before closing it.
