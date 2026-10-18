$ErrorActionPreference = "Continue"
$order = 59,60,50,51,52,54,55,56,57,61,62,53
foreach ($n in $order) {
  Write-Host "MERGE $n"
  gh pr checks $n --watch --required --interval 20 2>&1 | Out-Null
  gh pr merge $n --squash --delete-branch 2>&1
  if ($LASTEXITCODE -ne 0) { Write-Host "FAILED $n" } else { Write-Host "MERGED $n" }
}
