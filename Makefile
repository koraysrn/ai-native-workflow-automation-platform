# AWAP local tooling and one-command stack (issue #189).
#
# The stack targets are POSIX-sh only; they run the same on Linux, macOS and Windows (Git Bash).
# The kind targets are opt-in and download the binary into .bin/ so no global install is needed.

SHELL := /bin/sh

COMPOSE_FILE ?= infra/docker-compose.yml
PROFILE ?= full
NODE ?= node
KIND_VERSION ?= v0.20.0
KIND ?= .bin/kind

.PHONY: help install-deps setup-local-infra up up-minimal down reset seed provision wait logs ps config test-infra coverage-baseline coverage-baseline-check

help:
	@echo "AWAP local targets:"
	@echo "  make up           Start the full stack (MinIO, ClamAV, AI layer, observability), wait, provision and seed."
	@echo "  make up-minimal   Start only PostgreSQL, Redis, Kafka, Schema Registry, backend and frontend."
	@echo "  make down         Stop the full stack; volumes are kept."
	@echo "  make reset        Remove volumes and start a clean seeded stack."
	@echo "  make seed         Idempotently create the local tenant and verify login."
	@echo "  make provision    Idempotently create buckets, Kafka topics and the Debezium connector."
	@echo "  make test-infra   Run the provisioning/compose node:test suite and shell checks."
	@echo "  make coverage-baseline        Regenerate docs/coverage-baseline.md from the JaCoCo reports (issue #197)."
	@echo "  make coverage-baseline-check  Fail when the committed baseline document is stale."

# Issue #197: the baseline document is generated, never edited by hand. Run
# `cd backend && ./mvnw -B clean verify` first so both JaCoCo reports exist;
# JaCoCo appends to jacoco.exec, so an incremental build can over-report.
BASELINE_REPORTS = \
	--csv awap-identity=backend/identity/target/site/jacoco/jacoco.csv \
	--xml awap-identity=backend/identity/target/site/jacoco/jacoco.xml \
	--csv awap-common=backend/common/target/site/jacoco/jacoco.csv \
	--xml awap-common=backend/common/target/site/jacoco/jacoco.xml \
	--document docs/coverage-baseline.md

coverage-baseline:
	$(NODE) scripts/coverage-baseline.mjs $(BASELINE_REPORTS) \
		--emit docs/coverage-baseline.md \
		--delta backend/target/coverage-baseline-delta.md

coverage-baseline-check:
	$(NODE) scripts/coverage-baseline.mjs $(BASELINE_REPORTS) --check

# Full profile: every runtime dependency and application, including MinIO, ClamAV and the AI layer.
up:
	AI_LAYER_ENABLED=true $(NODE) infra/scripts/stack.mjs up --profile $(PROFILE) --compose-file $(COMPOSE_FILE)

# Minimal footprint for laptops: only the documented core services, with optional integrations off.
up-minimal:
	KAFKA_ENABLED=false CLAMAV_ENABLED=false AI_NODE_ENABLED=false AI_LAYER_ENABLED=false \
	OPENSEARCH_ENABLED=false FILE_MANAGE_BUCKETS=false \
	docker compose -f $(COMPOSE_FILE) up -d --build postgres redis kafka schema-registry backend frontend

down:
	$(NODE) infra/scripts/stack.mjs down --profile $(PROFILE) --compose-file $(COMPOSE_FILE)

reset:
	$(NODE) infra/scripts/stack.mjs reset --profile $(PROFILE) --compose-file $(COMPOSE_FILE)

seed:
	$(NODE) infra/scripts/stack.mjs seed

provision:
	$(NODE) infra/scripts/stack.mjs provision --profile $(PROFILE) --compose-file $(COMPOSE_FILE)

wait:
	$(NODE) infra/scripts/stack.mjs wait --profile $(PROFILE)

logs:
	docker compose -f $(COMPOSE_FILE) --profile $(PROFILE) logs -f

ps:
	docker compose -f $(COMPOSE_FILE) --profile $(PROFILE) ps

config:
	docker compose -f $(COMPOSE_FILE) --profile $(PROFILE) config

test-infra:
	$(NODE) --test "infra/test/*.test.mjs"
	bash -n infra/debezium/register-connector.sh
	@if command -v shellcheck >/dev/null 2>&1; then \
		shellcheck infra/debezium/register-connector.sh; \
	else \
		echo "shellcheck not installed; skipping (CI installs it)"; \
	fi

## Downloads the missing kind binary into the project-local .bin/ folder.
install-deps:
	@echo "Downloading kind: https://kind.sigs.k8s.io/dl/$(KIND_VERSION)/kind-linux-amd64"
	@mkdir -p .bin
	@curl -fsSL "https://kind.sigs.k8s.io/dl/$(KIND_VERSION)/kind-linux-amd64" -o "$(KIND)"
	@chmod +x "$(KIND)"
	@echo "kind ready: $(KIND) ($(KIND_VERSION))"

## Creates a local kind cluster and deploys the MinIO + ClamAV manifests.
setup-local-infra: install-deps
	@echo "Creating the local Kubernetes cluster..."
	@$(KIND) create cluster --config infra/kind-config.yaml
	@echo "Deploying MinIO and ClamAV..."
	@kubectl apply -f infra/manifests/minio-clamav.yaml
