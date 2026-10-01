param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$PreviousStage, [switch]$CheckOnly)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$Root=(Resolve-Path -LiteralPath $Root).Path
$PreviousStage=(Resolve-Path -LiteralPath $PreviousStage).Path
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control|Out-Null
$lock=[IO.File]::Open((Join-Path $control 'pipeline.lock'),'OpenOrCreate','ReadWrite','None')
function Read-Status($Path) {
    for($n=0;;$n++) {
        $stream=$null;$reader=$null
        try {
            $stream=[IO.File]::Open($Path,'Open','Read',([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
            return (ConvertFrom-Json -InputObject $reader.ReadToEnd())
        } catch {
            if($n -ge 49 -or $_.Exception.GetBaseException() -isnot [IO.IOException]){throw}
            Start-Sleep -Milliseconds 100
        } finally {
            if($null -ne $reader){$reader.Dispose()}elseif($null -ne $stream){$stream.Dispose()}
        }
    }
}
function Save-Pipeline($State,$Old,$Details) {
    $file=Join-Path $control 'pipeline_status.json'
    $text=ConvertTo-Json -InputObject ([pscustomobject]@{state=$State;updated=(Get-Date -Format o);
        previousStage=$PreviousStage;previousState=$Old;details=$Details;includeTrend=$true;maxParallel=4}) -Depth 3
    [IO.File]::WriteAllText("$file.tmp",$text,(New-Object Text.UTF8Encoding($false)))
    if([IO.File]::Exists($file)){[IO.File]::Replace("$file.tmp",$file,[NullString]::Value)}else{[IO.File]::Move("$file.tmp",$file)}
}
try {
    & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $Root -CheckOnly
    $cfg=[IO.File]::ReadAllText((Join-Path $Root 'config.json'))|ConvertFrom-Json
    if($cfg.includeTrend -ne $true){throw 'This independent stage must explicitly enable trend'}
    do {
        $status=Read-Status (Join-Path $PreviousStage 'control/status.json')
        $ready=$status.state -eq 'FINISHED' -and @($status.tasks).Count -eq 5 -and
            @($status.tasks|Where-Object state -in @('PENDING','RUNNING')).Count -eq 0
        $failed=@($status.tasks|Where-Object state -eq 'FAILED').Count
        # An independent new experiment can start after terminal failures; do not mislabel those as successes.
        Save-Pipeline $(if($ready){'READY'}else{'WAITING_EXISTING_OLIST_DRO'}) $status.state "previousFailedMarkets=$failed"
        if($CheckOnly){Write-Host "TREND_QUEUE_CHECK_PASS ready=$ready previousState=$($status.state) no_solves";return}
        if(!$ready){Start-Sleep -Seconds 60}
    } while(!$ready)
    Save-Pipeline 'RUNNING_TREND_BASELINES' $status.state 'D,SAA,EXP,RF; same frozen five markets'
    & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $Root
    Save-Pipeline 'RUNNING_TREND_DRO' $status.state 'One globally OOS-selected CSAA family; lambda 0.1,0.25,0.5,1'
    & (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1') -BaseRoot $Root -Root (Join-Path $Root 'dro_global_csaa')
    Save-Pipeline 'FINISHED' $status.state 'Inspect baseline and DRO task states; FINISHED alone does not imply no failed tasks'
} catch {
    Save-Pipeline 'FAILED' '' $_.Exception.Message
    throw
} finally {$lock.Dispose()}
