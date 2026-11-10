# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.1](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.1.0...v1.1.1) (2026-11-10)


### Bug Fixes

* **release:** generate SBOMs from the backend POM and add recovery ([#243](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/243)) ([#252](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/252)) ([4c2cc25](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4c2cc25fa7b7bdaf5bd1c569bda12d555b98864b))

## [1.1.0](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.0.4...v1.1.0) (2026-11-10)


### Features

* **ci:** coverage ratchet, changed-line gate and trend ([#196](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/196)) ([#250](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/250)) ([06fce80](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/06fce80a3c3f0b3f8248e7f2e11e3fce2cd3cb71))
* **identity:** propagate correlation IDs end to end ([#245](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/245)) ([9032343](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/90323439a7befe62185dae6dec2845c06b380668))
* **infra:** add a complete one-command local stack ([#241](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/241)) ([74a74df](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/74a74df82da0a5f101c4aaa5363ba74070ad6527))
* **observability:** prove metrics, traces and logs in CI ([#242](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/242)) ([344cc72](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/344cc72870fa79985c8c99c5b7abd22b745038ee))
* **openapi:** enforce controller and breaking-change contracts ([#246](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/246)) ([3c67c63](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/3c67c63e709bbf1e607ed839a1b3b8eecc131bc7))
* **organization:** assign roles through positions ([#194](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/194)) ([#248](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/248)) ([ada3d8f](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/ada3d8f765d4c6461001b54c1549e069f3f9de5a))
* **organization:** gate coverage and reject duplicates ([#195](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/195)) ([#249](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/249)) ([cd46992](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/cd4699294a62fb2b4d50efe4dbaca419b6f5cc8c))
* **sbom:** generate, merge, sign and gate SBOMs for every layer ([#247](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/247)) ([bc135de](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/bc135de7d1298b8f10b1f445a3df821be9920546))

## [1.0.4](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.0.3...v1.0.4) (2026-11-09)


### Bug Fixes

* **ci:** gate on the shipped SBOM and pin the vulnerable transitives ([#239](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/239)) ([eb56652](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/eb5665242d95513bcd896c8d096410a2a9e796e4))
* **ci:** verify the Argo CD CLI against the release checksum manifest ([#238](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/238)) ([82d35aa](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/82d35aa56f698d957ee2777c8c4f99e5e8fde4e4))

## [1.0.3](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.0.2...v1.0.3) (2026-11-09)


### Bug Fixes

* **k8s:** make the HPA effective with requests, behavior and PDBs ([#235](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/235)) ([13dadff](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/13dadff4edf8dc8e69bbba2571073ec7c7fca12a))
* **security:** deliver secrets from Vault with External Secrets ([#236](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/236)) ([e6cbf75](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/e6cbf75d81d270ecdb74066912b548b3561a70a5))

## [1.0.2](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.0.1...v1.0.2) (2026-11-09)


### Bug Fixes

* **frontend:** harden CSP with per-request nonces ([#230](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/230)) ([07dbdda](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/07dbdda9cb92380f1a87d2095d61e3e7a85ffa52))

## [1.0.1](https://github.com/koraysrn/ai-native-workflow-automation-platform/compare/v1.0.0...v1.0.1) (2026-11-08)


### Bug Fixes

* **release:** align the release PR component with the manifest ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#225](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/225)) ([adec124](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/adec124f301b2f54acc16db72d6cbc95ac9a3593))
* **release:** keep the release PR component empty for the root package ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#226](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/226)) ([aca2406](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/aca2406478487ee077f7bf472e7c8b9b2df8c5bd))

## 1.0.0 (2026-11-08)


### Features

* advanced workflow automation, notifications, documents and analytics ([#113](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/113)) ([9e15528](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/9e155281f0f45ba82963e94eed0f8f7f76282901))
* **ai:** enable the AI node and add a sandboxed SCRIPT node ([#155](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/155)) ([0518e2e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/0518e2eb44568160039e9ce3154d0c7cc355439f))
* **analytics:** harden OpenSearch sink with templates, retry and DLQ ([#147](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/147)) ([1c33a90](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/1c33a90015a7eb5af806b8563858e6580cb18b39))
* **cdc:** add Debezium outbox connector and measured cutover plan ([#154](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/154)) ([4fa2862](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4fa2862b63dcd4e41207cf34b78a687f26ce8340))
* close Faz 1 preflight security and reliability gaps ([#82](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/82)) ([ec40c2c](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/ec40c2c6874216c9186a18eb37ebfe5b7e7ec3e7))
* **contracts:** extend Pact suites, AI message contract and Avro registry compatibility ([#89](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/89)) ([#110](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/110)) ([98ace4c](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/98ace4ca50d89e6da52458898f60eea6801a4784))
* deliver the AWAP core platform services ([#25](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/25)) ([59547cf](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/59547cfe6f3c6dcd0f4b43dd86e2becb77952861))
* **designer:** add edge-level condition editing ([#134](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/134)) ([#168](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/168)) ([3f4e86d](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/3f4e86d1796910620fd31fd2f3f4757e86dbebc6))
* **documents:** stream live scan status updates ([#135](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/135)) ([#169](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/169)) ([2993bb9](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/2993bb9be990cfa8a936d9d8694faaffb47e1452))
* **engine:** add deterministic log-driven replay ([#139](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/139)) ([eeb3f9e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/eeb3f9eb39f9b0fcc378c331b75571332054882a))
* **engine:** add saga compensation for sub-process and integrations ([#133](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/133)) ([#166](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/166)) ([1c20c6b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/1c20c6badf0a5e31d753b8558fb68c0cf7c2a05e))
* **engine:** add workflow pause/resume operations ([#51](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/51)) ([ee1561f](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/ee1561f0f0740ee9c88350f779c2e8ccec1114dc))
* **engine:** dual-consume the legacy awap.dlq topic ([#123](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/123)) ([#146](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/146)) ([a1df3d7](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/a1df3d700c24adb3aeaa07b01b771e181d02b083))
* **engine:** enforce measurable node timeouts ([#138](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/138)) ([a5879bf](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/a5879bf2d051733e8937f91bc3998d74bc9c940f))
* **engine:** integrate Kafka Schema Registry with Avro schemas ([#58](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/58)) ([22b6ba8](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/22b6ba80bfa8bc222162ec38ae2aa43b7845e08c))
* **file:** quarantine uploads and promote only after clean scan ([#141](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/141)) ([847faf1](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/847faf13240afded0211476362c3eb5b0f1405ee))
* **frontend:** add shadcn/ui with button component ([8562f49](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/8562f49603bc65c7e1a1e0b8b780f4dfd157b66e))
* **frontend:** add tenant selection and switcher ([#53](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/53)) ([dd5e9c3](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/dd5e9c36b221aa116cca95022064d406cc5635ab))
* **governance:** apply and verify repository labels and milestones ([#180](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/180)) ([#217](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/217)) ([06d3c30](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/06d3c306ba71465fc8f2ae970e6d438b63fc28a9))
* **governance:** enforce main branch protection with drift detection ([#178](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/178)) ([#214](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/214)) ([64a5a7d](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/64a5a7daf6458bd518076cd0462dff9a8a566eb6))
* harden the AWAP platform (RBAC, RLS, ClamAV, workflow engine, Vault, frontend) ([#26](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/26)) ([b598061](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/b5980618b2831317f6ca2a305abf8e21215921b3))
* **identity:** add JPA tenant filter as defense-in-depth ([#55](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/55)) ([4911844](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4911844334ef4d70b4759206593961ee6c4b846f))
* **identity:** add tenant admin invitation flow ([#52](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/52)) ([379bc01](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/379bc010b63bed2c2b9204de240852b34cbde211))
* **identity:** allow SYSTEM_ADMIN cross-tenant limit updates under RLS ([#54](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/54)) ([0f90e98](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/0f90e985bf08b91eda5373f2346c73bcb9976666))
* implement parallel/merge gateways, calibrate DLQ alerts, prove Avro schema evolution ([#83](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/83)) ([4b64e18](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4b64e18c1e29e586a4a62e77706677f9ae9f39df))
* **load-test:** cover analytics, documents, notifications and webhooks in k6 ([#152](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/152)) ([4f4b502](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4f4b502101a33faeb124993fa464fd0cb994c94c))
* **modules:** implement notification, analytics, document and AI modules ([#97](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/97)) ([#100](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/100)) ([1a26ffb](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/1a26ffbd77a992833d856b2ba3956b6a36d72da9))
* **notification:** add read/unread state to in-app notifications ([#149](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/149)) ([4d3977d](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/4d3977d3d83b7c955089e3ba68862b0d86375dec))
* **notification:** add TTL and business-key dedupe policy ([#143](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/143)) ([ce278c9](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/ce278c9419f3e7f6aec796ba501726ecaffdfd18))
* **notification:** complete tenant template management ([#127](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/127)) ([#165](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/165)) ([f0b9b2e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/f0b9b2ed570b052bf08e736ad90fba47d660bc17))
* **notification:** manage tenant notification templates ([#150](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/150)) ([861a547](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/861a547e748d4e2f56967d890e899e2fa8e5dab2))
* **notification:** move retries into a leased scheduler ([#142](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/142)) ([64af0da](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/64af0da65ad5a35acbad9fae5b943be034b724cd))
* **observability:** add a Trivy regression signal ([#162](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/162)) ([9d3a4f5](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/9d3a4f58adb9b5769135aa657fda999443717125))
* **observability:** add task decision metric and Prometheus/Grafana DoD ([#61](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/61)) ([cfb150e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/cfb150e13bfde07b5cab04789761e4d20b5f4504))
* **observability:** add workflow, notification and outbox panels and alerts ([#153](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/153)) ([f70744a](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/f70744a8e938bc3752b8be38dc4132fe23524fa5))
* **ops:** automate backup/DR, trace verification, secrets gate and compliance inventory ([#98](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/98)) ([#99](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/99)) ([21a1adf](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/21a1adfc1f305368c338a8eeb8ad593491575134))
* **organization:** publish organization change events via outbox ([#57](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/57)) ([0a0a906](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/0a0a90631e82f95897f8b8efdb3dc3db5c957288))
* **outbox:** fail fast on the JPA relay fallback and expose the active relay path ([#145](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/145)) ([46d9906](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/46d9906b63175ff4aa28ba06d87d38e72a24940a))
* **outbox:** reconcile rows claimed but never published ([#144](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/144)) ([365ca81](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/365ca817697ed97278f600f4383b82c1e65eca18))
* raise coverage gate, add ADR-003 module skeletons, partition scheduler and SLO alerts ([#84](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/84)) ([3c2cb8f](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/3c2cb8fa8928e48f9b7d1197a9532a03e7dbce9c))
* **release:** automate versioning and changelog with release-please ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#215](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/215)) ([0e1433b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/0e1433b2618a607491161e474fc269cc53edae89))
* scaffold AWAP monorepo (backend, frontend, ai-layer, infra, CI/CD) ([dab1524](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/dab15246a25873bcc3bb4ec507bfe5eb12942226))
* **security:** replace in-memory auth rate limiter with Redis Lua script ([#95](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/95)) ([#102](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/102)) ([21e128e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/21e128eef287584c1a7e923bd80e873d4beeca25))
* **tasks:** persist decision comment on approve/reject ([#50](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/50)) ([49c90c8](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/49c90c8fcbc12f791b9949bf33404dc7f71416ee))
* **usage:** tenant usage metering and quota telemetry ([#148](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/148)) ([ead2f6e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/ead2f6e593bf6937fc4ca2d1f8c83e48d9f7ac43))
* **webhook:** durable retry queue and DLQ pipeline for webhook nodes ([#137](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/137)) ([a9757a1](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/a9757a1232396290e5308599ee14a7e856c2e209))
* **workflow:** pin and validate SUB_PROCESS definition version ([#140](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/140)) ([5bae215](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/5bae2151d9fe255bdb38c260b4a917d5df3453b3))


### Bug Fixes

* **ai-layer:** build the uv image from the python 3.15 base instead of a missing uv tag ([#64](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/64)) ([758a28a](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/758a28aed1c73372e559d729b8b2a8823dac2ce2))
* **ci:** avoid secrets context in conditional expressions for OWASP step ([6ad7c0b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/6ad7c0b7b5af7ca3119ed52bf480a6ec55d6d2ff))
* **ci:** generate Next.js route types before typecheck and use v-prefixed trivy-action tag ([d8bc5dc](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/d8bc5dc46b6e6a67e13a4c776d2891dce8ff2e91))
* **ci:** install modules before OWASP scan to resolve sibling dependency ([d7f7d57](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/d7f7d5705d3b244eca72ee9a69d42922d21e12c7))
* **ci:** move OWASP NVD API key guard to step level ([1c8aa7b](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/1c8aa7b76b35a9720d3a2008abf2b6a82ccbe53c))
* **governance:** keep read-only drift checks independent of merge settings ([#180](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/180)) ([#219](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/219)) ([3412984](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/3412984ec3a98ae3f304fb1fc2322a8f28c115e0))
* **load-test:** stop failing the nightly k6 run on unauthenticated requests ([#177](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/177)) ([304db38](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/304db3800e2cba729bfa0e7945ab439f3e85f5cf))
* **release:** detect app credentials outside the workflow if expression ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#221](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/221)) ([d7fd64e](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/d7fd64ed9ce9b557e0e331ba874a382276f8fbaf))
* **release:** support a GitHub App token for release automation ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#220](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/220)) ([8c9a5c6](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/8c9a5c6e46a9016aa7650c132676f8d24ebce77c))
* **release:** trust the app mint step instead of repo push permissions ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#223](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/223)) ([a066449](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/a06644992b6924c238cd11a1690b084cc380cb3b))
* **release:** verify GitHub App installation permissions in the preflight ([#179](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/179)) ([#222](https://github.com/koraysrn/ai-native-workflow-automation-platform/issues/222)) ([446abd0](https://github.com/koraysrn/ai-native-workflow-automation-platform/commit/446abd029f9afb5fef75c3e21728859af4ab4934))

## [Unreleased]

### Added

- Monorepo scaffold (backend, frontend, ai-layer, infra)
- Pilot version compatibility verification (see `COMPATIBILITY.md`)
- Repository governance (CODEOWNERS, PR template, commitlint, Renovate, Dependabot)
- CI/CD foundations (see `.github/workflows`)
- Advanced workflow nodes: DECISION, CONDITION, WEBHOOK, SUB_PROCESS and explicit
  PARALLEL_GATEWAY/MERGE_GATEWAY aliases, with node-level timeout and retry.
- Deterministic replay/recovery with external-call suppression and a recovery endpoint.
- Notification delivery ledger (idempotent consumer), localized templates, web channel,
  exponential backoff and per-topic dead-letter queues.
- Document API with presigned MinIO uploads/downloads, version history and tenant-scoped access.
- Analytics aggregates in PostgreSQL, an optional OpenSearch raw-event sink and a dashboard API.
- Dedicated outbox relay connection pool, configurable batch size and SECURITY DEFINER relay
  functions so the relay works under row-level security.
- Kafka consumer tenant context so notification/analytics/document writes satisfy RLS.
- Frontend designer support for the new node types plus Analytics and Documents screens.
- OpenAPI contract entries for the new document, notification, analytics and recovery endpoints.
- Live document scan status over tenant-scoped SSE, with a reconnect/polling hook, in-place row
  updates, PROMOTED-only download gating and INFECTED/accessibility announcements.
- Saga compensation for SUB_PROCESS and external integrations: durable `saga_instances`/
  `saga_steps`, reverse-order compensation, per-step retries/timeouts, DLQ, crash recovery,
  manual intervention API, audit rows and metrics.
- Live-stack Playwright journey covering the seeded tenant, dashboard, workflow start, task
  decision, document scan projection and analytics aggregates, with CORS headers for
  `X-Tenant-Id`/`X-XSRF-TOKEN`, an SPA-compatible CSRF bootstrap endpoint/handler, an opt-out for
  runtime bucket provisioning, and distinct Kafka consumer groups for the analytics/usage
  listeners that share a topic.
- Workflow start now advances the managed entity returned by `save()`, so the persisted instance
  reaches `COMPLETED` instead of remaining `CREATED` while the API response reported the advanced
  state.
- Measured polling-vs-CDC outbox benchmark from the Compose stack: published-lag samples, p50/p95/max,
  the `evaluateCutover()` verdict, a committed baseline and CI artifacts. The CDC deployment now uses
  `wal_level=logical`, partition-aware topic filtering, `REPLICA IDENTITY FULL` (V50) and the
  Debezium image's environment contract.
- Trivy regression signal for the dependency gate: a CVE severity breakdown and annotations in the
  CI job summary, a retained JSON artifact, and a tracking issue after two consecutive failing
  `main` runs. The CI-only decision is recorded in ADR-0005 and the triage procedure in runbook O21.
- Enforced branch protection for `main`: an idempotent apply/verify/dry-run script with the required
  check names defined once and validated against the actual CI jobs, a scheduled drift workflow with
  a dedicated admin token, and an explicit audited single-maintainer break-glass procedure (runbook
  O22). Every script mode and error path is exercised in CI.
