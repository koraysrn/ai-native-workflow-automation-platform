.PHONY: setup-local-infra

setup-local-infra:
	@echo "Creating local Kubernetes cluster..."
	kind create cluster --config infra/kind-config.yaml
	@echo "Deploying infrastructure services..."
	kubectl apply -f infra/manifests/minio-clamav.yaml
