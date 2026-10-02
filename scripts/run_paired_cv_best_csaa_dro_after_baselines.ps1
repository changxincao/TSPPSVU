param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot,
    [ValidateRange(1, 4)][int]$MaxParallel = 4,
    [switch]$ResumeActiveDro,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$cells = @('cv010030', 'cv030050', 'cv040060', 'cv050070')
$runner = Join-Path $TaskRoot 'scripts\run_paired_cv_oos_selected_dro_remote.ps1'
$java = Join-Path $TaskRoot 'runtime\java\bin\java.exe'
$baselineStatus = Join-Path $ExperimentRoot 'control\unified20\status.json'
$control = Join-Path $ExperimentRoot 'control\dro20_after_baselines'
$lambdaGrid = '0.1,0.25,0.5,1'
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')

if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Missing runner: $runner" }
if ($DryRun) {
    [pscustomobject]@{
        cells = $cells -join ','; replicationsPerCell = 20; queriesPerReplication = 40
        selection = 'LOW_RF_AND_TRI_OTHER_CELLS_RF'; lambdaGrid = $lambdaGrid
        methodMarketTasks = 100; lowBranches = 'RF-CSAA,CSAA-Tri'
        parallel = $MaxParallel; solverThreads = 4; limitSeconds = 14400
        waitForExistingBaselines = $true; changesExistingWorkers = $false
    }
    return
}

New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock = [IO.File]::Open((Join-Path $control 'run.lock'), [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$failed = [System.Collections.Generic.List[string]]::new()
function Write-Status([string]$state, [string]$cell, [string]$detail) {
    $json = [pscustomobject]@{
        state = $state; updated = [DateTime]::Now.ToString('o'); pid = $PID
        cell = $cell; detail = $detail; failedCells = @($failed.ToArray())
        replicationsPerCell = 20; queriesPerReplication = 40
        selection = 'LOW_RF_AND_TRI_OTHER_CELLS_RF'; formalTrainingOnly = $false
        methodMarketTasks = 100; lowBranches = 'RF-CSAA,CSAA-Tri'
        lambdaGrid = $lambdaGrid; parallel = $MaxParallel; solverThreads = 4
    } | ConvertTo-Json -Depth 4
    $temporary = Join-Path $control 'status.pending.json'
    Set-Content -LiteralPath $temporary -Value $json -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination (Join-Path $control 'status.json') -Force
}
function Active-Solvers {
    @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $java })
}
function Record-Failure([string]$cell, [string]$method, [string]$errorText) {
    $failed.Add("$cell/$method")
    [pscustomobject]@{ time = [DateTime]::Now.ToString('o'); cell = $cell; contextMethod = $method; error = $errorText } |
        Export-Csv -LiteralPath (Join-Path $control 'failed_cells.csv') -Append -NoTypeInformation -Encoding UTF8
}
function Run-Branch([string]$cell, [string]$method, [int]$slots) {
    $slotFile=Join-Path $control ("slots_${cell}_${method}.txt")
    Set-Content -LiteralPath $slotFile -Value $slots -Encoding ASCII
    $tag = '{0}_{1}_{2}' -f $cell,$method,(Get-Date -Format yyyyMMdd_HHmmss_fff)
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $runner + '"'),
        '-TaskRoot', ('"' + $TaskRoot + '"'), '-ExperimentRoot', ('"' + $ExperimentRoot + '"'),
        '-Cell', $cell, '-ContextMethod', $method, '-ReplicationCount', 20,
        '-MaxParallel', $slots, '-SolverThreads', 4, '-LimitSeconds', 14400, '-LambdaGrid', $lambdaGrid,
        '-ParallelControlFile', ('"'+$slotFile+'"'))
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList ($arguments -join ' ') `
        -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $control "$tag.stdout.log") `
        -RedirectStandardError (Join-Path $control "$tag.stderr.log")
    $heldHandle = $process.Handle
    [pscustomobject]@{ process = $process; cell = $cell; method = $method; slots = $slots; slotFile=$slotFile }
}
function Wait-Branches($branches) {
    while($true){
        $live=@($branches | Where-Object {-not $_.process.HasExited})
        if($live.Count -eq 0){break}
        if($live.Count -eq 1 -and $live[0].slots -ne $MaxParallel){
            # An exited scheduler may leave native workers alive: do not steal their slots.
            $remainingRoot=Join-Path $ExperimentRoot ($live[0].cell+'\experiment2_fixed_csaa\'+$live[0].method+'\')
            $otherWorkers=@(Active-Solvers | Where-Object {-not ([OlistWindowsProcess]::CommandLine($_.Id)).Contains($remainingRoot)})
            if($otherWorkers.Count -eq 0){
                Set-Content -LiteralPath $live[0].slotFile -Value $MaxParallel -Encoding ASCII
                $live[0].slots=$MaxParallel
            }
        }
        Start-Sleep -Seconds 2
    }
    foreach ($branch in $branches) {
        try {
            $branch.process.WaitForExit()
            if ($branch.process.ExitCode -ne 0) {
                Record-Failure $branch.cell $branch.method "DRO stage exit code $($branch.process.ExitCode)"
            }
        } finally { $branch.process.Dispose() }
    }
    # A failed branch must not make the next stage overlap surviving Java workers.
    while (@(Active-Solvers).Count -gt 0) { Start-Sleep -Seconds 30 }
}

try {
    Write-Status 'WAITING_BASELINES' '' 'Existing baseline queue retains all four slots; no solvers stopped.'
    while ($true) {
        try { $status = Get-Content -LiteralPath $baselineStatus -Raw -Encoding UTF8 | ConvertFrom-Json }
        catch { Start-Sleep -Seconds 5; continue }
        if ($status.state -in @('FINISHED', 'FAILED', 'PARTIAL')) {
            $active=@(Active-Solvers)
            if($active.Count -eq 0){break}
            if($ResumeActiveDro){
                foreach($p in $active){
                    $line=[OlistWindowsProcess]::CommandLine($p.Id)
                    if(-not $line.Contains('Test.analysis.synthetic.TRBSVUExperiment2IdeMain') -or -not $line.Contains($ExperimentRoot+'\cv010030\experiment2_fixed_csaa\')){throw "Refuse takeover of unrelated worker $($p.Id)"}
                }
                break
            }
        }
        Start-Sleep -Seconds 30
    }
    foreach ($cell in $cells) {
        Write-Status 'RUNNING' $cell 'Fixed CSAA family; preserve per-market training-selected parameters and branch-specific checkpoints.'
        $branches = [System.Collections.Generic.List[object]]::new()
        try {
            if ($cell -eq 'cv010030') {
                if ($MaxParallel -eq 1) {
                    foreach ($method in @('RF-CSAA', 'CSAA-Tri')) {
                        $branch = Run-Branch $cell $method 1
                        Wait-Branches @($branch)
                    }
                } else {
                    $rfSlots = [int][Math]::Floor($MaxParallel / 2)
                    $branches.Add((Run-Branch $cell 'RF-CSAA' $rfSlots))
                    $branches.Add((Run-Branch $cell 'CSAA-Tri' ($MaxParallel - $rfSlots)))
                }
            } else { $branches.Add((Run-Branch $cell 'RF-CSAA' $MaxParallel)) }
        } catch {
            Record-Failure $cell 'STAGE_LAUNCH' $_.Exception.Message
        }
        Wait-Branches $branches.ToArray()
    }
    Write-Status $(if ($failed.Count -eq 0) { 'FINISHED' } else { 'PARTIAL' }) '' 'All requested DRO cells attempted; per-task checkpoints retained.'
} catch {
    Write-Status 'FAILED' '' $_.Exception.Message
    throw
} finally { $lock.Dispose() }
