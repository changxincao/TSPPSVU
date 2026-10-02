param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot,
    [ValidateRange(1, 4)][int]$MaxParallel = 4,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$cells = @('cv030050', 'cv040060', 'cv010030', 'cv050070')
$runner = Join-Path $TaskRoot 'scripts\run_paired_cv_oos_selected_dro_remote.ps1'
$java = Join-Path $TaskRoot 'runtime\java\bin\java.exe'
$baselineStatus = Join-Path $ExperimentRoot 'control\unified20\status.json'
$control = Join-Path $ExperimentRoot 'control\dro20_after_baselines'
$lambdaGrid = '0.1,0.25,0.5,1'

if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Missing runner: $runner" }
if ($DryRun) {
    [pscustomobject]@{
        cells = $cells -join ','; replicationsPerCell = 20; queriesPerReplication = 40
        selection = 'CELL_GLOBAL_BEST_OOS_MEAN'; lambdaGrid = $lambdaGrid
        parallel = $MaxParallel; solverThreads = 4; limitSeconds = 14400
        waitForExistingBaselines = $true; changesExistingWorkers = $false
    }
    return
}

New-Item -ItemType Directory -Force -Path $control | Out-Null
$failed = [System.Collections.Generic.List[string]]::new()
function Write-Status([string]$state, [string]$cell, [string]$detail) {
    $json = [pscustomobject]@{
        state = $state; updated = [DateTime]::Now.ToString('o'); pid = $PID
        cell = $cell; detail = $detail; failedCells = @($failed.ToArray())
        replicationsPerCell = 20; queriesPerReplication = 40
        selection = 'CELL_GLOBAL_BEST_OOS_MEAN'; formalTrainingOnly = $false
        lambdaGrid = $lambdaGrid; parallel = $MaxParallel; solverThreads = 4
    } | ConvertTo-Json -Depth 4
    $temporary = Join-Path $control 'status.pending.json'
    Set-Content -LiteralPath $temporary -Value $json -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination (Join-Path $control 'status.json') -Force
}
function Active-Solvers {
    @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $java })
}

try {
    Write-Status 'WAITING_BASELINES' '' 'Existing baseline queue retains all four slots; no solvers stopped.'
    while ($true) {
        $status = Get-Content -LiteralPath $baselineStatus -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($status.state -in @('FINISHED', 'FAILED', 'PARTIAL') -and @(Active-Solvers).Count -eq 0) { break }
        Start-Sleep -Seconds 30
    }
    foreach ($cell in $cells) {
        if (@(Active-Solvers).Count -ne 0) { throw 'Unexpected active solvers before starting the next DRO cell.' }
        Write-Status 'RUNNING' $cell 'One global CSAA family per cell; retain per-market validation-selected parameters.'
        try {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $runner `
                -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot -Cell $cell `
                -ReplicationCount 20 -MaxParallel $MaxParallel -SolverThreads 4 `
                -LimitSeconds 14400 -LambdaGrid $lambdaGrid
            if ($LASTEXITCODE -ne 0) { throw "DRO stage exited with code $LASTEXITCODE" }
        } catch {
            $failed.Add($cell)
            [pscustomobject]@{ time = [DateTime]::Now.ToString('o'); cell = $cell; error = $_.Exception.Message } |
                Export-Csv -LiteralPath (Join-Path $control 'failed_cells.csv') -Append -NoTypeInformation -Encoding UTF8
            # Never overlap surviving workers from a failed scheduler with the next cell.
            while (@(Active-Solvers).Count -gt 0) { Start-Sleep -Seconds 30 }
        }
    }
    Write-Status $(if ($failed.Count -eq 0) { 'FINISHED' } else { 'PARTIAL' }) '' 'All requested DRO cells attempted; per-task checkpoints retained.'
} catch {
    Write-Status 'FAILED' '' $_.Exception.Message
    throw
}
