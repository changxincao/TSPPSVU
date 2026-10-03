param([Parameter(Mandatory=$true)][string]$Root,[switch]$Start,[switch]$CheckOnly,[switch]$ResumeOverlap)
$ErrorActionPreference='Stop'
$Root=(Resolve-Path -LiteralPath $Root).Path
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control|Out-Null
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
if($Start){
    function Q($s){"'"+$s.Replace("'","''")+"'"}
    $log=Join-Path $control "pipeline_$(Get-Date -Format yyyyMMdd_HHmmss_fff).log"
    $body='$ErrorActionPreference=''Stop''; try { & '+(Q $PSCommandPath)+' -Root '+(Q $Root)+
        $(if($ResumeOverlap){' -ResumeOverlap'}else{''})+
        ' *>&1 | Tee-Object -FilePath '+(Q $log)+' } catch { $_ | Out-String | Add-Content -LiteralPath '+(Q $log)+'; exit 1 }'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
    $startedPid=[OlistWindowsProcess]::StartDetached('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
        "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded",$Root)
    [pscustomobject]@{pid=$startedPid;started=(Get-Date -Format o);root=$Root;log=$log}|
        ConvertTo-Json|Set-Content -LiteralPath (Join-Path $control 'launcher.json') -Encoding UTF8
    Write-Output "DETACHED_RF_DRO_PIPELINE_STARTED pid=$startedPid"
    return
}
$lock=[IO.File]::Open((Join-Path $control 'pipeline.lock'),'OpenOrCreate','ReadWrite','None')
$cfg=Get-Content -LiteralPath (Join-Path $Root 'followup.json') -Raw|ConvertFrom-Json
$dro=Join-Path $Root 'dro_rf'
function Read-Live($Path){
    $stream=[IO.File]::Open($Path,'Open','Read',([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader=[IO.StreamReader]::new($stream)
    try{return ($reader.ReadToEnd()|ConvertFrom-Json)}finally{$reader.Dispose()}
}
function Save($State,$Details){
    $file=Join-Path $control 'pipeline_status.json'
    $text=[pscustomobject]@{state=$State;details=$Details;updated=(Get-Date -Format o);
        previousRoot=$cfg.previousRoot;seedStart=$cfg.seedStart;marketCount=10;
        parallel=4;solverThreads=4;lambdaGrid=$cfg.lambdaGrid}|ConvertTo-Json
    [IO.File]::WriteAllText("$file.tmp",$text,[Text.UTF8Encoding]::new($false))
    if([IO.File]::Exists($file)){[IO.File]::Replace("$file.tmp",$file,[NullString]::Value)}else{[IO.File]::Move("$file.tmp",$file)}
}
try{
    if(!$ResumeOverlap){& (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $Root -CheckOnly}
    & (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1') -BaseRoot $Root -Root $dro -LambdaGrid $cfg.lambdaGrid -FixedRf -CheckOnly
    do{
        $previous=Read-Live (Join-Path $cfg.previousRoot 'control/pipeline_status.json')
        $ready=$previous.state -in @('FINISHED','FINISHED_WITH_FAILURES')
        Save 'WAITING_PREVIOUS' $previous.state
        if($CheckOnly){Write-Output "FOLLOWUP_CHECK_PASS predecessor=$($previous.state) no_solves";return}
        if($previous.state -eq 'FAILED'){throw 'Predecessor controller failed; inspect orphan workers before resuming'}
        if(!$ready){Start-Sleep -Seconds 30}
    }while(!$ready)
    $orphans=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object {
        [OlistWindowsProcess]::CommandLine($_.Id).Contains([string]$cfg.previousRoot)
    })
    if($orphans.Count){throw 'Predecessor has live Java workers; refuse overlap'}
    Save $(if($ResumeOverlap){'RUNNING_RF_DRO'}else{'RUNNING_RF'}) '10 markets, each 51 rolling predictions; shared four-slot pool when overlapping'
    & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $Root -AdoptRunning:$ResumeOverlap -OverlapFixedRf:$ResumeOverlap
    $baseline=Read-Live (Join-Path $control 'status.json')
    if(@($baseline).Count -ne 10 -or @($baseline|Where-Object state -notin @('COMPLETE','FAILED')).Count){
        throw 'RF scheduler has not reached a terminal state; inspect possible live workers'
    }
    Save 'RUNNING_DRO' 'RF parameters selected separately each week; lambda selected on 15 preceding origins'
    & (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1') -BaseRoot $Root -Root $dro -LambdaGrid $cfg.lambdaGrid -FixedRf
    $robust=Read-Live (Join-Path $dro 'control/status.json')
    $expected=@($baseline|ForEach-Object market|Sort-Object)
    $actual=@($robust.tasks|ForEach-Object market|Sort-Object)
    if($robust.state -notin @('FINISHED','FINISHED_WITH_FAILURES') -or $actual.Count -ne 10 -or
        ($expected -join ',') -ne ($actual -join ',') -or
        @($robust.tasks|Where-Object state -notin @('COMPLETE','FAILED','BLOCKED_BASELINE')).Count){
        throw 'DRO terminal status missing or incomplete; cannot mark pipeline finished'
    }
    $failed=@($robust.tasks|Where-Object state -ne 'COMPLETE').Count
    Save $(if($failed){'FINISHED_WITH_FAILURES'}else{'FINISHED'}) "DRO incomplete markets=$failed"
}catch{Save 'FAILED' $_.Exception.Message;throw}finally{$lock.Dispose()}
