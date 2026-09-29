param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot
)

$ErrorActionPreference = 'Stop'
$statusFile = Join-Path $ExperimentRoot 'followup_relauncher_status.txt'
$launcherRecord = Join-Path $ExperimentRoot `
    'run_main_screen_25_followup_after_repair_remote_launcher_process.txt'
$followupStatus = Join-Path $ExperimentRoot 'followup_after_repair_status.txt'

function Write-Status([string]$state, [string[]]$details) {
    @(
        "state=$state"
        "updated=$([DateTime]::Now.ToString('o'))"
        $details
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

if (-not (Test-Path -LiteralPath $launcherRecord -PathType Leaf)) {
    throw "Missing current follow-up launcher record: $launcherRecord"
}
$pidLine = Get-Content -LiteralPath $launcherRecord |
    Where-Object { $_ -like 'controllerPid=*' } | Select-Object -First 1
if ($null -eq $pidLine) {
    throw "Missing controllerPid in $launcherRecord"
}
$currentControllerPid = [int]($pidLine -replace '^controllerPid=', '')
Write-Status 'WAITING_FOR_CURRENT_PASS' @(
    "currentControllerPid=$currentControllerPid"
    'next=checkpoint-aware C-Chi2 repair, then remaining contextual methods'
)
while (Get-Process -Id $currentControllerPid -ErrorAction SilentlyContinue) {
    Start-Sleep -Seconds 30
}

$state = if (Test-Path -LiteralPath $followupStatus -PathType Leaf) {
    Get-Content -LiteralPath $followupStatus |
        Where-Object { $_ -like 'state=*' } | Select-Object -First 1
} else {
    'state=MISSING'
}
if ($state -eq 'state=FINISHED') {
    Write-Status 'NOT_NEEDED' @('reason=current pass already finished')
    exit 0
}

Write-Status 'RUNNING_CHECKPOINT_AWARE_REPAIR' @(
    "previousState=$state"
    'note=completed replications are reused; only incomplete tasks solve again'
)
& (Join-Path $TaskRoot 'scripts\run_main_screen_25_followup_after_repair_remote.ps1') `
    -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot
$exitCode = $LASTEXITCODE
if ($null -eq $exitCode) { $exitCode = 0 }
if ($exitCode -ne 0) {
    Write-Status 'FAILED' @("exitCode=$exitCode")
    exit $exitCode
}
Write-Status 'FINISHED' @('note=checkpoint-aware repair and queued phases completed')
