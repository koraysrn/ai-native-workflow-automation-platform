# Repository Governance

Bu belge `main` dalının korunması, etiket (label) taksonomisi, milestone yapısı ve merge politikasını kod olarak tanımlar. Uygulama için idempotent script: [`scripts/apply-repo-governance.ps1`](../scripts/apply-repo-governance.ps1).

## Branch protection — `main`

| Kural | Değer |
|---|---|
| Require pull request | Evet |
| Direct push | Yasak |
| Enforce admins | Evet |
| Require conversation resolution | Evet |
| Dismiss stale reviews | Evet |
| Required approving review count | 0 |
| Require code owner reviews | Hayır (aşağıdaki nota bakın) |
| Allow force pushes | Hayır |
| Allow deletions | Hayır |
| Strict status checks | Evet |

> **Not — code owner review:** Repo tek maintainer'lı olduğundan (`@koraysrn`) `require_code_owner_reviews` kapalıdır. Açık tutulması, yazarın kendi PR'ını onaylayamaması nedeniyle tüm PR'ları kilitleyebilir. Ek maintainer/lar eklendiğinde bu kural açılmalıdır.

### Required status checks

- `Backend (Java 25 / Spring Boot 4.1.1)`
- `Frontend (TypeScript 6.x / Next.js 16)`
- `AI Layer (Python 3.14 / FastAPI)`
- `Trivy vulnerability scan`
- `Secret scan (Gitleaks)`
- `OWASP Dependency Check`
- `SonarQube analysis`
- `Docker build (backend, frontend, ai-layer)`

## Merge policy

- Squash merge zorunlu; merge commit ve rebase kapalı.
- Head branch merge sonrası otomatik silinir.
- Commit mesajları Conventional Commits'e uygun olmalıdır ([`commitlint.config.cjs`](../commitlint.config.cjs)).

## Labels

| Etiket | Açıklama |
|---|---|
| `kind/bug` | Hata düzeltmesi |
| `kind/feature` | Yeni özellik veya istek |
| `kind/chore` | Bakım, araç veya altyapı işi |
| `area/backend` | Backend (Java / Spring Boot) |
| `area/frontend` | Frontend (Next.js / React) |
| `area/ai` | AI layer (Python / FastAPI) |
| `priority/p0` | Kritik — ilk gönderilecek |
| `priority/p1` | Yüksek öncelik |
| `priority/p2` | Orta öncelik |
| `priority/p3` | Düşük öncelik |
| `phase/preparation` | Preparation phase (repo, CI/CD, security foundations) |
| `phase/core-mvp` | Core MVP phase |
| `phase/expansion` | Post-MVP expansion |

## Milestones

| Milestone | Kapsam |
|---|---|
| `Preparation Phase` | Repo, CI/CD and security foundations |
| `Phase 0 - Core MVP` | Core MVP |
| `Phase 1 - Expansion` | Post-MVP expansion |
