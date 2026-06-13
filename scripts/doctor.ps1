param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$Json
)

$ErrorActionPreference = "Stop"
$normalizedBaseUrl = $BaseUrl.TrimEnd("/")
$doctorUrl = "$normalizedBaseUrl/api/doctor/status"

try {
    $snapshot = Invoke-RestMethod -Method Get -Uri $doctorUrl -Headers @{ Accept = "application/json" } -TimeoutSec 8
} catch {
    Write-Host "Doctor unavailable: $doctorUrl"
    Write-Host $_.Exception.Message
    exit 2
}

if ($Json) {
    $snapshot | ConvertTo-Json -Depth 8
} else {
    Write-Host "Arklight doctor: $($snapshot.status), issues=$($snapshot.issueCount), checkedAt=$($snapshot.checkedAt)"
    foreach ($check in $snapshot.checks) {
        if ($check.status -eq "OK") {
            continue
        }
        Write-Host ""
        Write-Host "[$($check.status)] $($check.label) ($($check.id))"
        Write-Host "  $($check.detail)"
        if ($check.path) {
            Write-Host "  path: $($check.path)"
        }
        if ($check.action) {
            Write-Host "  action: $($check.action)"
        }
    }
}

if ($snapshot.status -eq "ERROR") {
    exit 2
}
if ($snapshot.status -eq "WARN") {
    exit 1
}
exit 0
