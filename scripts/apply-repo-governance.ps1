[CmdletBinding()]
param(
    [string]$Repo = "koraysrn/ai-native-workflow-automation-platform"
)

$ErrorActionPreference = "Stop"

Write-Host "== Applying repository governance to $Repo =="

# 1. Merge policy: squash merge + delete head branch
gh api -X PATCH "repos/$Repo" `
    -f allow_squash_merge=true `
    -f allow_merge_commit=false `
    -f allow_rebase_merge=false `
    -f delete_branch_on_merge=true | Out-Null
Write-Host "[OK] Squash merge enabled, merge/rebase disabled, head branch auto-delete enabled"

# 2. Issue/PR label taxonomy
$labels = @(
    @{ n = "kind/bug";        d = "Bug fix or correction";                        c = "d73a4a" },
    @{ n = "kind/feature";    d = "New feature or request";                       c = "a2eeef" },
    @{ n = "kind/chore";      d = "Maintenance, tooling or infra work";           c = "c5def5" },
    @{ n = "area/backend";    d = "Backend (Java / Spring Boot)";                 c = "ffa221" },
    @{ n = "area/frontend";   d = "Frontend (Next.js / React)";                   c = "168700" },
    @{ n = "area/ai";         d = "AI layer (Python / FastAPI)";                  c = "5319e7" },
    @{ n = "priority/p0";     d = "Critical - must ship first";                   c = "b60205" },
    @{ n = "priority/p1";     d = "High priority";                                c = "d93f0b" },
    @{ n = "priority/p2";     d = "Medium priority";                              c = "fbca04" },
    @{ n = "priority/p3";     d = "Low priority";                                 c = "0e8a16" },
    @{ n = "phase/preparation"; d = "Preparation phase (repo, CI/CD, security foundations)"; c = "e4e669" },
    @{ n = "phase/core-mvp";    d = "Core MVP phase";                             c = "0e8a16" },
    @{ n = "phase/expansion";   d = "Post-MVP expansion";                         c = "0075ca" }
)

foreach ($l in $labels) {
    gh label create $l.n --description $l.d --color $l.c --force | Out-Null
}
Write-Host "[OK] Label taxonomy applied ($($labels.Count) labels)"

# 3. Milestones (idempotent)
function Ensure-Milestone {
    param([string]$Title, [string]$Description)
    $all = gh api "repos/$Repo/milestones" | ConvertFrom-Json
    $existing = $all | Where-Object { $_.title -eq $Title } | Select-Object -First 1
    if ($null -eq $existing) {
        gh api "repos/$Repo/milestones" -f title=$Title -f description=$Description | Out-Null
        Write-Host "[OK] Milestone created: $Title"
    } else {
        Write-Host "[SKIP] Milestone already exists: $Title (#$($existing.number))"
    }
}

Ensure-Milestone "Preparation Phase"  "Preparation phase - repo, CI/CD and security foundations"
Ensure-Milestone "Phase 0 - Core MVP" "Phase 0 - core MVP"
Ensure-Milestone "Phase 1 - Expansion" "Phase 1 - post-MVP expansion"

# 4. Branch protection on main (policy lives in settings/branch-protection.json)
$policy = Join-Path $PSScriptRoot "..\settings\branch-protection.json"
if (-not (Test-Path $policy)) {
    throw "Missing branch protection policy: $policy"
}
gh api -X PUT "repos/$Repo/branches/main/protection" --input $policy | Out-Null
Write-Host "[OK] Branch protection applied to main"

Write-Host "== Done =="
