param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot,
    [ValidateRange(1, 4)][int]$MaxParallel = 4
)

$ErrorActionPreference = 'Stop'
$cells = @('cv030050', 'cv040060', 'cv010030', 'cv050070')
$control = Join-Path $ExperimentRoot 'control\unified20'
New-Item -ItemType Directory -Force -Path $control | Out-Null

function Write-Status([string]$state, [string]$phase, [string]$cell, [string]$detail) {
    $json = [pscustomobject]@{
        state = $state
        updated = [DateTime]::Now.ToString('o')
        phase = $phase
        cell = $cell
        detail = $detail
        replicationsPerCell = 20
        seeds = '20261020-20261039'
        outputRoot = $ExperimentRoot
        parallel = $MaxParallel
        solverThreads = 4
    } | ConvertTo-Json
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        try {
            Set-Content -LiteralPath (Join-Path $control 'status.json') -Value $json `
                -Encoding UTF8 -ErrorAction Stop
            return
        } catch [System.IO.IOException] {
            if ($attempt -eq 40) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
}

try {
    $audit = Join-Path $ExperimentRoot 'unified20_input_audit.csv'
    if (-not (Test-Path -LiteralPath $audit) -or @(Import-Csv -LiteralPath $audit).Count -ne 80) {
        throw 'Unified 20-replication input audit is missing or incomplete.'
    }
    foreach ($cell in $cells) {
        Write-Status 'RUNNING' 'BASELINES' $cell 'complete all 20 replications; reuse verified completed outputs'
        & powershell -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $TaskRoot 'scripts\run_paired_cv_extension_baselines_remote.ps1') `
            -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot -Cell $cell `
            -FirstSeed 20261020 -ReplicationCount 20 -MaxParallel $MaxParallel -SkipCompleted
        if ($LASTEXITCODE -ne 0) { throw "Baseline stage failed for $cell" }

        # Finish every CV cell's baselines before the user reviews CSAA results.
        # DRO is deliberately not launched by this pipeline.
    }
    Write-Status 'FINISHED' 'BASELINES_ONLY' '' `
        'all four CV cells baselines completed; DRO awaits user approval'
} catch {
    Write-Status 'FAILED' '' '' $_.Exception.Message
    throw
}
