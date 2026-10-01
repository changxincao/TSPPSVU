param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot
)

$ErrorActionPreference = 'Stop'
& powershell -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $TaskRoot 'scripts\run_paired_cv_20_unified_pipeline_remote.ps1') `
    -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot -MaxParallel 4
exit $LASTEXITCODE
