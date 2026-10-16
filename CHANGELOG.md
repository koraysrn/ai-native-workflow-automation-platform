# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.0.0 (2026-10-16)


### Features

* deliver the AWAP core platform services ([#25](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/25)) ([59547cf](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/59547cfe6f3c6dcd0f4b43dd86e2becb77952861))
* **frontend:** add shadcn/ui with button component ([8562f49](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/8562f49603bc65c7e1a1e0b8b780f4dfd157b66e))
* scaffold AWAP monorepo (backend, frontend, ai-layer, infra, CI/CD) ([dab1524](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/dab15246a25873bcc3bb4ec507bfe5eb12942226))


### Bug Fixes

* **ci:** avoid secrets context in conditional expressions for OWASP step ([6ad7c0b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/6ad7c0b7b5af7ca3119ed52bf480a6ec55d6d2ff))
* **ci:** generate Next.js route types before typecheck and use v-prefixed trivy-action tag ([d8bc5dc](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/d8bc5dc46b6e6a67e13a4c776d2891dce8ff2e91))
* **ci:** install modules before OWASP scan to resolve sibling dependency ([d7f7d57](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/d7f7d5705d3b244eca72ee9a69d42922d21e12c7))
* **ci:** move OWASP NVD API key guard to step level ([1c8aa7b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/1c8aa7b76b35a9720d3a2008abf2b6a82ccbe53c))

## [Unreleased]

### Added

- Monorepo scaffold (backend, frontend, ai-layer, infra)
- Pilot version compatibility verification (see `COMPATIBILITY.md`)
- Repository governance (CODEOWNERS, PR template, commitlint, Renovate, Dependabot)
- CI/CD foundations (see `.github/workflows`)
