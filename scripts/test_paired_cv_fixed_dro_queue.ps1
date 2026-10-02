param([string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'analysis_runs'))
$ErrorActionPreference = 'Stop'
$root = Join-Path $OutputRoot ('fixed_dro_queue_selfcheck_' + [guid]::NewGuid().ToString('N'))
$task = Join-Path $root 'task'
$experiment = Join-Path $root 'experiment'
New-Item -ItemType Directory -Force -Path "$task\scripts", "$experiment\control\unified20" | Out-Null
# Simulate schedulers, not native optimizers. One failed stage must not stop later cells.
$mock = @'
param($TaskRoot,$ExperimentRoot,$Cell,$ContextMethod,$ReplicationCount,$MaxParallel,$SolverThreads,$LimitSeconds,$LambdaGrid,$ParallelControlFile)
$ErrorActionPreference='Stop'
$directory=Join-Path $ExperimentRoot "mock_calls\$Cell\$ContextMethod"
New-Item -ItemType Directory -Force -Path $directory|Out-Null
[pscustomobject]@{cell=$Cell;method=$ContextMethod;reps=$ReplicationCount;slots=$MaxParallel;threads=$SolverThreads;limit=$LimitSeconds;lambda=$LambdaGrid}|ConvertTo-Json|Set-Content (Join-Path $directory 'call.json')
if($Cell-eq'cv010030' -and $ContextMethod-eq'RF-CSAA'){
    $deadline=[DateTime]::Now.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        $slots=(Get-Content -LiteralPath $ParallelControlFile -Raw).Trim()
    } while($slots-ne'4' -and [DateTime]::Now-lt$deadline)
    if($slots-ne'4'){throw 'Freed TRI slots were not transferred to RF'}
    Set-Content (Join-Path $directory 'expanded.txt') '4'
}
if($Cell-eq'cv030050'){exit 2}
exit 0
'@
Set-Content -LiteralPath "$task\scripts\run_paired_cv_oos_selected_dro_remote.ps1" -Value $mock -Encoding UTF8
'{"state":"FINISHED"}' | Set-Content -LiteralPath "$experiment\control\unified20\status.json"
& powershell -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot 'run_paired_cv_best_csaa_dro_after_baselines.ps1') `
    -TaskRoot $task -ExperimentRoot $experiment -MaxParallel 4
if ($LASTEXITCODE -ne 0) { throw 'Mock queue failed unexpectedly' }
$calls = @(Get-ChildItem -LiteralPath "$experiment\mock_calls" -Recurse -Filter call.json |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
if ($calls.Count -ne 5) { throw "Expected five branches, found $($calls.Count)" }
if(-not(Test-Path "$experiment\mock_calls\cv010030\RF-CSAA\expanded.txt")){throw 'Dynamic slot transfer failed'}
foreach ($call in $calls) {
    if ($call.reps -ne 20 -or $call.threads -ne 4 -or $call.limit -ne 14400 -or $call.lambda -ne '0.1,0.25,0.5,1') {
        throw 'Worker settings changed'
    }
    if ($call.cell -eq 'cv010030') {
        if ($call.slots -ne 2 -or $call.method -notin @('RF-CSAA','CSAA-Tri')) { throw 'Low branch allocation incorrect' }
    } elseif ($call.slots -ne 4 -or $call.method -ne 'RF-CSAA') { throw 'Other CV cells are not fixed RF' }
}
$status = Get-Content -LiteralPath "$experiment\control\dro20_after_baselines\status.json" -Raw | ConvertFrom-Json
if ($status.state -ne 'PARTIAL' -or @($status.failedCells).Count -ne 1 -or $status.failedCells[0] -ne 'cv030050/RF-CSAA') {
    throw 'A stage failure was lost or stopped the remaining queue'
}
Write-Output "PASS: low RF/Tri 2+2 slots, other cells RF/4 slots, four lambda values, later stages continue after failure; no native solves. $root"
