param(
    [Parameter(Mandatory=$true)][string]$FormalExperiment,
    [Parameter(Mandatory=$true)][string]$PilotScript,
    [Parameter(Mandatory=$true)][string]$Deploy,
    [Parameter(Mandatory=$true)][string]$InputRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
while ((Get-Content -LiteralPath (Join-Path $FormalExperiment 'status.txt') -Raw).Trim().StartsWith('RUNNING')) {
    Start-Sleep -Seconds 30
}
$formalStatus = (Get-Content -LiteralPath (Join-Path $FormalExperiment 'status.txt') -Raw).Trim()
if (-not $formalStatus.StartsWith('COMPLETED')) {
    throw "Formal Experiment 1 did not complete successfully: $formalStatus"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
Set-Content -LiteralPath (Join-Path $OutputRoot 'status.txt') -Value ('RUNNING ' + (Get-Date -Format o))
try {
    & $PilotScript -Deploy $Deploy -InputRoot $InputRoot -OutputRoot $OutputRoot
    Set-Content -LiteralPath (Join-Path $OutputRoot 'exit_code.txt') -Value 0
    Set-Content -LiteralPath (Join-Path $OutputRoot 'status.txt') `
        -Value ('COMPLETED ' + (Get-Date -Format o))
} catch {
    Set-Content -LiteralPath (Join-Path $OutputRoot 'exit_code.txt') -Value 1
    Set-Content -LiteralPath (Join-Path $OutputRoot 'status.txt') `
        -Value ('FAILED ' + (Get-Date -Format o))
    throw
}
