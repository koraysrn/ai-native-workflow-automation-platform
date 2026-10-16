# Lokal altyapı araçları ve küme kurulumu.
# Varsayılan olarak Windows (cmd.exe + PowerShell) için yapılandırılmıştır.
# Unix/macOS kullanıcıları KIND ve KIND_URL değişkenlerini kendileri için geçersiz kılabilir.

KIND_VERSION ?= v0.20.0
KIND := .bin/kind.exe
KIND_URL := https://kind.sigs.k8s.io/dl/$(KIND_VERSION)/kind-windows-amd64

.PHONY: install-deps setup-local-infra

## Eksik kind aracını projeye özel .bin/ klasörüne indirir (global kurulum gerektirmez).
install-deps:
	@echo "kind indiriliyor: $(KIND_URL)"
	@if not exist .bin mkdir .bin
	@powershell -NoProfile -Command "Invoke-WebRequest -Uri '$(KIND_URL)' -OutFile '$(KIND)'"
	@echo "kind hazir: $(KIND) ($(KIND_VERSION))"

## Lokal kind kümesini oluşturur ve MinIO + ClamAV altyapısını deploy eder.
setup-local-infra: install-deps
	@echo "Lokal Kubernetes kümesi oluşturuluyor..."
	@$(KIND) create cluster --config infra/kind-config.yaml
	@echo "MinIO ve ClamAV deploy ediliyor..."
	@kubectl apply -f infra/manifests/minio-clamav.yaml
