# Break-glass log — incident #243

Incident [#243](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/243)
authorised the single-maintainer break-glass merges for the sequential issues #189–#196.
This page is the committed audit trail required by runbook
[O22](operations-runbook.md#o22--branch-protection-drift-and-break-glass-issue-178).
The machine-readable source is [`settings/break-glass-log.json`](../settings/break-glass-log.json);
the tests fail when this table and the JSON diverge.

## Incident scope

| Field | Value |
|-------|-------|
| Incident | #243 |
| Owner approving the exception | `@koraysrn` |
| Reason | One maintainer; GitHub never counts a self-approval against `required_approving_review_count=1` |
| Expiry | `2026-11-11T23:59:59Z` |
| Scope | Only the approval count is lowered to 0 for one merge at a time |
| Unchanged | `enforce_admins=true`, required status checks, force-push/deletion blocks, drift workflow |
| Restore | `scripts/apply-branch-protection.sh --apply` then `--verify` immediately after every merge |

## Merged PRs

Every row required all required status checks to be green **before** the approval count was
lowered. Restore was executed immediately after the merge.

| Issue | PR | Merge commit | Merged at (UTC) | Checks green | Restore `--apply` | Restore `--verify` |
|-------|----|--------------|-----------------|--------------|-------------------|--------------------|
| #189 | #241 | `74a74df82da0a5f101c4aaa5363ba74070ad6527` | 2026-11-10T13:38:45Z | yes | updated | matches |
| #190 | #242 | `344cc72870fa79985c8c99c5b7abd22b745038ee` | 2026-11-10T14:31:50Z | yes | updated | matches |
| #191 | #245 | `90323439a7befe62185dae6dec2845c06b380668` | 2026-11-10T15:14:50Z | yes | updated | matches |
| #192 | #246 | `3c67c63e709bbf1e607ed839a1b3b8eecc131bc7` | 2026-11-10T15:44:22Z | yes | updated | matches |
| #193 | #247 | `bc135de7d1298b8f10b1f445a3df821be9920546` | 2026-11-10T16:22:20Z | yes | updated | matches |
| #194 | #248 | `ada3d8f765d4c6461001b54c1549e069f3f9de5a` | 2026-11-10T17:45:05Z | yes | updated | matches |
| #195 | #249 | `cd4699294a62fb2b4d50efe4dbaca419b6f5cc8c` | 2026-11-10T18:44:06Z | yes | updated | matches |
| #196 | #250 | `06fce80a3c3f0b3f8248e7f2e11e3fce2cd3cb71` | 2026-11-10T19:19:57Z | yes | updated | matches |

## Post-incident proof

| Check | Value |
|-------|-------|
| Live approval count | `1` |
| `enforce_admins` | `true` |
| Expected drift failure while the exception was active | run `42878185620` (restored immediately afterwards) |
| Final drift run (green) | run `42884190955` |
| Green `main` CI commit | `06fce80a3c3f0b3f8248e7f2e11e3fce2cd3cb71` |

The drift failure during the exception is expected and is documented in O22 as the reminder to
restore protection; it is not muted or disabled.

## Closure rule

Incident #243 is closed by the governance PR that introduced this log. It cannot authorise any
further merge. Every future single-maintainer break-glass must:

1. open a **new** incident with its own reason, scope, owner and expiry;
2. append its merges to [`settings/break-glass-log.json`](../settings/break-glass-log.json) and this
   table in the same PR;
3. restore protection with `--apply` and `--verify` immediately after each merge; and
4. attach the restore output, the final drift run and the green `main` CI run before closing it.
