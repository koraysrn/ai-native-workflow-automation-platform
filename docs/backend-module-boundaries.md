# Backend module boundaries

- **Status:** Accepted (issue #181)
- **Decision record:** [ADR-001](adr/0001-monorepo-and-architecture-decisions.md)
- **Enforcement:** ArchUnit rules, Maven Enforcer, module dependency graph check

## Decision

The backend keeps **package-level layers** inside a small set of Maven modules.
ADR-001 is the single source of truth: the repository does **not** split into
`awap-domain`, `awap-application`, `awap-infrastructure` and `awap-interfaces`
Maven modules.

```
backend/
├── common/      shared kernel: RFC 7807 errors, API versioning, cross-module ports
└── identity/    deployable modular monolith
    └── com.awap.<module>/
        ├── domain/          entities, value objects, domain services (no framework logic)
        ├── application/     use cases, ports, transaction boundaries
        ├── infrastructure/  adapters, JPA repositories, external clients
        └── interfaces/      controllers, DTOs, event listeners
```

The target module graph is the current one: `common` is the only shared Maven
module, and every feature module lives as a package tree under `identity`.
Dependencies always point inward at the layer level:

```
interfaces -> application -> domain
infrastructure -> application -> domain
```

Cross-module calls are allowed only through application services or shared
contracts in `common`; a module must not reach into another module's
`infrastructure` or `interfaces` packages.

## Domain purity exception (documented)

Issue #199 and [ADR-0007](adr/0007-jpa-domain-entity-exception.md) make the
exception **narrow and time-boxed**. Only the classes named in
[`jpa-domain-exception.txt`](../backend/identity/src/test/resources/architecture/jpa-domain-exception.txt)
may carry JPA mapping annotations (`jakarta.persistence.Entity`, `Table`,
`Column`, ...) or the Spring Data / Hibernate mapping annotations. Every other
domain class fails ArchUnit if it imports them. The inventory is 37 classes and
the exception expires on **2027-06-30**; the migration plan is in ADR-0007.

The architecture gate therefore forbids **persistence machinery** in the domain
instead of all mapping annotations:

- `jakarta.persistence.EntityManager`, `EntityManagerFactory`, `Query`,
  `TypedQuery`, `EntityTransaction`, `Persistence`, `criteria.*`, metamodel
- `org.springframework.data.repository.*` and `org.springframework.data.jpa.*`
- `org.hibernate.*` **except** `org.hibernate.annotations.*`

Spring DI, web, security, transaction and context packages remain forbidden in
domain code; only `org.springframework.data.annotation.*` mapping annotations are
allowed **on the named exception classes**.

Adding a JPA-annotated domain class therefore requires: an ADR-0007 review, a
new line in the exception file, and an inventory entry in this document. A stale
entry, a class outside the list and an expired review date all fail the
`JpaDomainExceptionTest`.

## Enforcement

| Gate | Location | Fails when |
|---|---|---|
| Domain has no Spring/JPA machinery | [`ModularArchitectureTest`](../backend/identity/src/test/java/com/awap/architecture/ModularArchitectureTest.java) | A domain class imports a forbidden framework package |
| JPA exception is narrow and current | [`JpaDomainExceptionTest`](../backend/identity/src/test/java/com/awap/architecture/JpaDomainExceptionTest.java) | An annotated domain class is not named in the exception, an entry is stale, or the review date has passed |
| Domain does not see outer layers | [`ModularArchitectureTest`](../backend/identity/src/test/java/com/awap/architecture/ModularArchitectureTest.java) | A domain class imports `application`, `infrastructure` or `interfaces` |
| Rules are not a no-op | [`ArchitectureRuleNegativeTest`](../backend/identity/src/test/java/com/awap/architecture/ArchitectureRuleNegativeTest.java) | A deliberately leaky fixture stops failing the rule |
| Split layer modules never appear | `maven-enforcer-plugin` in [`backend/pom.xml`](../backend/pom.xml) | A POM declares `awap-domain`, `awap-application`, `awap-infrastructure` or `awap-interfaces` |
| No new module cycle | [`scripts/module-dependency-graph.mjs`](../scripts/module-dependency-graph.mjs) | A cycle group is not in `scripts/module-cycles-baseline.json`, or a baseline entry is stale |

Run the checks locally:

```bash
cd backend
./mvnw -B verify
cd ..
node --test scripts/test/module-dependency-graph.test.mjs
node scripts/module-dependency-graph.mjs --check
node scripts/module-dependency-graph.mjs --dot > /tmp/awap-modules.dot
```

## Cycle migration debt

The current code base has one documented cycle group:

```
ai, engine, identity, organization, script, task, usage, webhook, workflow
```

The cycle is recorded in
[`scripts/module-cycles-baseline.json`](../scripts/module-cycles-baseline.json).
This is a ratchet, not an approval:

1. A **new** cycle group fails `--check`.
2. Fixing a cycle without pruning the baseline also fails `--check`, so the
   debt cannot silently reappear.
3. The target is an empty baseline. The migration plan is:
   - move shared event contracts (`NodeCompletedEvent`, outbox port) into
     `common`;
   - replace direct cross-module repository access with ports owned by the
     calling module;
   - move shared identity primitives (for example `Role`) into `common`;
   - delete each baseline entry as soon as its cycle is broken.
