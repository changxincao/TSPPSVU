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

        Write-Status 'RUNNING' 'OOS_SELECTED_C_CHI2' $cell `
            'this cell baselines complete; select best CSAA by each replication 40-query OOS Mean'
        & powershell -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $TaskRoot 'scripts\run_paired_cv_oos_selected_dro_remote.ps1') `
            -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot -Cell $cell `
            -ReplicationCount 20 -MaxParallel $MaxParallel -SolverThreads 4 `
            -LimitSeconds 14400 -LambdaGrid '0.1,0.25,0.5,1'
        if ($LASTEXITCODE -ne 0) { throw "DRO stage failed for $cell" }
    }
    Write-Status 'FINISHED' '' '' 'all four CV cells and 20 replications completed'
} catch {
    Write-Status 'FAILED' '' '' $_.Exception.Message
    throw
}
