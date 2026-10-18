$ErrorActionPreference = "Continue"

$map = @{
  49 = "chore/31-ai-uv-python315"
  63 = "chore/20-argocd-gitops"
  58 = "chore/41-avro-schema-registry"
  59 = "chore/27-jacoco-gate"
  60 = "chore/34-pit-mutation"
  50 = "chore/29-task-decision-comment"
  51 = "chore/39-workflow-pause-resume"
  52 = "chore/38-tenant-invitations"
  54 = "chore/30-system-admin-rls"
  55 = "chore/40-jpa-tenant-filter"
  56 = "chore/28-kafka-embedded-test"
  57 = "chore/37-organization-outbox-events"
  61 = "chore/35-observability-dod"
  62 = "chore/43-load-test"
  53 = "chore/42-tenant-switcher"
}

foreach ($n in 49,63,58,59,60,50,51,52,54,55,56,57,61,62,53) {
  $b = $map[$n]
  Write-Host "=== PR $n ($b) ==="
  git checkout $b | Out-Null
  git fetch origin | Out-Null
  git merge origin/main --no-edit 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "CONFLICT on $b - skipping"
    git merge --abort 2>&1 | Out-Null
    continue
  }
  git push origin $b 2>&1 | Out-Null
  gh pr checks $n --watch --interval 20 2>&1 | Out-Null
  gh pr merge $n --squash --delete-branch 2>&1
  if ($LASTEXITCODE -ne 0) {
    Write-Host "MERGE FAILED for $n - will need manual retry"
  } else {
    Write-Host "MERGED $n"
  }
}
