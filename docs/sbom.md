# Software Bill of Materials

Every release publishes a CycloneDX SBOM for each runtime layer and one merged inventory
(issue #193).

## Generated artifacts

| Artifact | Generator | Contents |
|---|---|---|
| `backend.cdx.json` | CycloneDX Maven plugin (`makeAggregateBom`) | Resolved Maven dependency tree (exact BOM-resolved versions). |
| `frontend.cdx.json` | Syft (`syft dir:frontend`) | pnpm lockfile and `node_modules` inventory. |
| `ai-layer.cdx.json` | Syft (`syft dir:ai-layer`) | `uv.lock` and the installed virtual environment. |
| `awap-sbom.cdx.json` | `scripts/sbom/sbom.mjs merge` | De-duplicated release inventory with per-layer component counts. |
| `*.bundle` | cosign keyless (`sign-blob`) | Sigstore bundle for each SBOM. |

Artifacts are attached to the GitHub Release. CI also uploads the `sbom-inventory` artifact for
pull requests. A missing layer, an invalid CycloneDX document, a schema violation or a disallowed
license fails the release before the assets are signed or uploaded.

## Validation and merge

```bash
node scripts/sbom/sbom.mjs validate --file sbom/backend.cdx.json --layer backend
node scripts/sbom/sbom.mjs merge \
  --backend sbom/backend.cdx.json \
  --frontend sbom/frontend.cdx.json \
  --ai sbom/ai-layer.cdx.json \
  --output sbom/awap-sbom.cdx.json
node scripts/sbom/sbom.mjs licenses --file sbom/awap-sbom.cdx.json
cyclonedx validate --input-file sbom/awap-sbom.cdx.json --input-format json
```

The merge de-duplicates by `purl` (falling back to `type:name@version`) and records
`awap:layers` plus `awap:layer:<name>:components` metadata properties.

## License policy

[`scripts/sbom/license-policy.json`](../scripts/sbom/license-policy.json) allows permissive and weak
copyleft licenses and denies strong copyleft (GPL/AGPL/SSPL). Components without license metadata
are printed as warnings for manual review; a disallowed identifier in a license expression is
release-blocking.

## Verify a release SBOM

```bash
gh release download "$TAG" --pattern '*.cdx.json' --pattern '*.bundle'
cosign verify-blob \
  --bundle awap-sbom.cdx.json.bundle \
  --certificate-identity-regexp 'https://github.com/koraysrn/ai-native-workflow-automation-platform/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  awap-sbom.cdx.json
cyclonedx validate --input-file awap-sbom.cdx.json --input-format json
```

Consumers should use the merged inventory for dependency and license audits and a single layer SBOM
only when they need that runtime's exact graph.
