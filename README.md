# AI-Native Workflow Automation Platform (AWAP)

A next-generation workflow management platform that digitizes, automates, and
optimizes operational business processes with artificial intelligence. AWAP is
the AI-era counterpart of classic BPM, workflow automation, and service
management systems — combining no-code process design, automatic execution
based on the organizational structure, and agentic AI under a single roof.

## Repository Layout

This is a **monorepo** hosting all layers and services:

```
.
├── backend/        # Java 25 + Spring Boot 4.1.1 (Maven multi-module)
├── frontend/       # Next.js 16 + TypeScript 7.0.2 (pnpm workspace)
├── ai-layer/       # Python 3.15 + FastAPI + LangChain/LangGraph (conda)
├── infra/          # Docker Compose, Kubernetes manifests, observability
├── docs/adr/       # Architecture Decision Records
├── .github/        # CI/CD workflows, PR template, dependabot
├── environment.yml # AI Layer conda environment (single source of truth)
└── COMPATIBILITY.md# Verified version compatibility matrix
```

## Technology Stack

| Layer | Technology | Fixed Version |
|---|---|---|
| Backend | Java | 25 (LTS) |
| Backend | Spring Boot | 4.1.1 |
| Backend | Build tool | Maven 3.9.x |
| Frontend | TypeScript | 6.x (ESLint-compatible) |
| Frontend | Next.js | 16.4.x |
| Frontend | Node.js | 24 LTS |
| Frontend | Package manager | pnpm |
| AI Layer | Python | 3.14 (conda) |
| AI Layer | FastAPI / LangChain / LangGraph | pinned via `environment.yml` |

> The full, verified compatibility matrix lives in [`COMPATIBILITY.md`](COMPATIBILITY.md).

## Prerequisites

- JDK 25 (Temurin) — `JAVA_HOME` must point to it
- Maven 3.9.x (or use the Maven Wrapper `./mvnw`)
- Node.js 24 LTS + pnpm
- Miniforge / conda (for the AI Layer)
- Docker + Docker Compose (for local infrastructure)

## Getting Started

### 1. Local infrastructure

```bash
cd infra
docker compose up -d
```

This starts PostgreSQL, Redis, Kafka (+ Schema Registry), MinIO, OpenSearch,
Prometheus, Grafana, the OpenTelemetry collector, and Vault.

### 2. AI Layer (conda)

```bash
conda env create -f environment.yml
conda activate awap-ai
cd ai-layer
ruff check .
uvicorn app.main:app --reload
```

### 3. Backend (Java)

```bash
cd backend
./mvnw verify
```

### 4. Frontend (Next.js)

```bash
cd frontend
pnpm install
pnpm typecheck
pnpm dev
```

## Quality Gates

Every PR must pass the automated gates defined in `.github/workflows/ci.yml`:

- Build + unit + integration tests
- Static analysis (ArchUnit, Checkstyle, SonarQube, ESLint, Ruff)
- OWASP Dependency Check + Trivy vulnerability scan
- Contract tests (Pact) and coverage gates
- SBOM generation

## Documentation

- [`docs/intent.md`](docs/intent.md) — product intent and goals
- [`docs/spec.md`](docs/spec.md) — technical specification
- [`CLAUDE.md`](CLAUDE.md) — project constitution (binding rules)
- [`docs/adr/`](docs/adr/) — Architecture Decision Records

## License

[MIT](LICENSE)
