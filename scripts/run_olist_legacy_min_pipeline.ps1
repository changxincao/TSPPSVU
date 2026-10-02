param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$PreviousStage)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$Root=(Resolve-Path -LiteralPath $Root).Path
$PreviousStage=(Resolve-Path -LiteralPath $PreviousStage).Path
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock=[IO.File]::Open((Join-Path $control 'pipeline.lock'),'OpenOrCreate','ReadWrite','None')
function Save-State($State,$Details) {
    $path=Join-Path $control 'pipeline_status.json'
    $value=[pscustomobject]@{state=$State;details=$Details;updated=(Get-Date -Format o);
        previousStage=$PreviousStage;parallel=4;solverThreads=4;includeTrend=$true;seeds=@(0,1,2)}
    [IO.File]::WriteAllText("$path.tmp",(ConvertTo-Json -InputObject $value -Depth 4),(New-Object Text.UTF8Encoding($false)))
    if([IO.File]::Exists($path)){[IO.File]::Replace("$path.tmp",$path,[NullString]::Value)}else{[IO.File]::Move("$path.tmp",$path)}
}
try {
    # Full deployment preflight is performed once before launch; each actual scheduler
    # rechecks its dependencies on entry. Do not repeat RF imports while waiting.
    do {
        $path=Join-Path $PreviousStage 'control/pipeline_status.json'
        $stream=[IO.File]::Open($path,'Open','Read',([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        try { $reader=[IO.StreamReader]::new($stream); try {$previous=ConvertFrom-Json -InputObject $reader.ReadToEnd()}finally{$reader.Dispose()} }
        finally {$stream.Dispose()}
        if($previous.state -eq 'FAILED'){throw 'Previous pipeline failed; do not overlap potentially orphaned workers'}
        Save-State 'WAITING_PREVIOUS' $previous.state
        if($previous.state -ne 'FINISHED'){Start-Sleep -Seconds 30}
    } while($previous.state -ne 'FINISHED')
    $failedGroups=@()
    foreach($name in @('I10','I15')) {
        $base=Join-Path $Root $name
        try {
            Save-State "RUNNING_${name}_CSAA" 'EXP and RF, independent rolling CV'
            & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $base
            Save-State "RUNNING_${name}_DRO" 'One global CSAA family per carrier scale; lambda 0.1,0.25,0.5,1'
            & (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1') -BaseRoot $base -Root (Join-Path $base 'dro_global_csaa')
            $dro=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText((Join-Path $base 'dro_global_csaa/control/status.json')))
            if(@($dro.tasks | Where-Object state -ne 'COMPLETE').Count){throw 'Some DRO markets failed after retries'}
        } catch {
            $failedGroups+=$name
            [IO.File]::WriteAllText((Join-Path $control "${name}_failure.txt"),($_ | Out-String))
            Save-State "FAILED_${name}_CONTINUING" $_.Exception.Message
            Write-Warning "$name failed; saved successes retained; continue next independent carrier scale"
        }
    }
    Save-State $(if($failedGroups.Count){'FINISHED_WITH_FAILURES'}else{'FINISHED'}) ("failedGroups="+($failedGroups -join ','))
} catch {
    Save-State 'FAILED' $_.Exception.Message
    throw
} finally {$lock.Dispose()}
