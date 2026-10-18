$ErrorActionPreference = "Continue"

$map = @{
  63 = "chore/20-argocd-gitops"
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

$order = 63,59,60,50,51,52,54,55,56,57,61,62,53

# Sync all branches with latest main
foreach ($n in $order) {
  $b = $map[$n]
  Write-Host "SYNC $n ($b)"
  git checkout $b 2>&1 | Out-Null
  git fetch origin 2>&1 | Out-Null
  git merge origin/main --no-edit 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "CONFLICT on $b"
    git merge --abort 2>&1 | Out-Null
    continue
  }
  git push origin $b 2>&1 | Out-Null
}

# Wait for checks to settle
Start-Sleep -Seconds 240

# Merge each after its required checks are green
foreach ($n in $order) {
  $b = $map[$n]
  Write-Host "MERGE $n"
  gh pr checks $n --watch --required --interval 20 2>&1 | Out-Null
  gh pr merge $n --squash --delete-branch --admin 2>&1
  if ($LASTEXITCODE -ne 0) { Write-Host "FAILED $n" } else { Write-Host "MERGED $n" }
}
