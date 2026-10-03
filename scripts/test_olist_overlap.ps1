param([Parameter(Mandatory=$true)][string]$TestRoot,[switch]$FailRf)
$ErrorActionPreference='Stop'
# Native-process identity stub for this isolated scheduler test only.
Add-Type 'public static class OlistWindowsProcess { public static string FixtureCommand = ""; public static string CommandLine(int pid) { return FixtureCommand; } }'
$global:fixtureAdopting=$false
function Get-Process {
    param($Name,$ErrorAction)
    if($global:fixtureAdopting){return $global:fixtureLiveWorker}
    return @()
}
if(Test-Path -LiteralPath $TestRoot){throw 'Use a fresh isolated fixture directory'}
New-Item -ItemType Directory -Path $TestRoot|Out-Null
$base=Join-Path $TestRoot 'base';$dro=Join-Path $base 'dro_rf'
foreach($p in @('inputs','control','scripts','runtime/classes','dro_rf/control','dro_rf/runtime/classes','lib','native')){
    New-Item -ItemType Directory -Force -Path (Join-Path $base $p)|Out-Null
}
$fake=Join-Path $TestRoot 'fake_java.ps1'
$fakeText=@'
if($args -contains '-c' -or $args -contains 'check' -or $args -contains 'prepare' -or $args -contains 'selfcheck'){$global:LASTEXITCODE=0;return}
if($args -contains 'select'){$global:LASTEXITCODE=0;return}
if($args -contains 'audit'){
    $isDro=$args -contains 'Test.analysis.brazil.OlistBestCsaaDroRunner'
    $market=@($args|Where-Object {$_ -match '^market_\d{3}$'})[0]
    $key=$market+$(if($isDro){':RF_CHI2'}else{':RF'})
    $global:LASTEXITCODE=if($global:fixtureComplete.ContainsKey($key)){0}else{1};return
}
throw 'Unexpected optimizer command'
'@
[IO.File]::WriteAllText($fake,$fakeText)
foreach($p in @('lib/cplex.jar','lib/mosek.jar','native/cplex2211.dll','scripts/rf_leaf_weights.py')){
    [IO.File]::WriteAllText((Join-Path $base $p),'fixture')
}
[pscustomobject]@{java=$fake;python=$fake;cplexJar=(Join-Path $base 'lib/cplex.jar');mosekJar=(Join-Path $base 'lib/mosek.jar');
    cplexNative=(Join-Path $base 'native');methods=@('RF');includeTrend=$true;fixedTrend104=$true;
    maxParallel=4;solverThreads=4;limitSeconds=14400;heap='2g';maxAttempts=2}|
    ConvertTo-Json|Set-Content -LiteralPath (Join-Path $base 'config.json') -Encoding UTF8
[pscustomobject]@{lambdaGrid='0.01,0.05,0.1,0.25,0.5,1,2,5,10'}|
    ConvertTo-Json|Set-Content -LiteralPath (Join-Path $base 'followup.json') -Encoding UTF8
0..9|ForEach-Object {[pscustomobject]@{market=('market_{0:000}' -f $_);market_seed=(351022+$_);rf_seed=20261020;instance='fixture'}}|
    Export-Csv -LiteralPath (Join-Path $base 'inputs/markets.tsv') -Delimiter "`t" -NoTypeInformation
$global:fixtureComplete=@{};$global:fixtureStarts=[Collections.Generic.List[string]]::new()
$global:fixtureFailRf=$FailRf.IsPresent
0..7|ForEach-Object {$global:fixtureComplete[('market_{0:000}:RF' -f $_)]=$true}
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    if(@($tasks|Where-Object state -eq 'RUNNING').Count -ge 4){throw 'Shared slot cap exceeded'}
    $text=$ArgumentList -join ' ';$market=[regex]::Match($text,'market_\d{3}').Value
    $kind=if($text.Contains('OlistBestCsaaDroRunner')){'RF_CHI2'}else{'RF'}
    if($kind -eq 'RF_CHI2' -and !$global:fixtureComplete.ContainsKey($market+':RF')){throw 'DRO started before RF ready'}
    $key=$market+':'+$kind
    $failure=$global:fixtureFailRf -and $key -eq 'market_009:RF'
    if($global:fixtureStarts.Contains($key) -and !$failure){throw 'Duplicate/recomputed task'}
    $global:fixtureStarts.Add($key)
    if(!$failure){$global:fixtureComplete[$key]=$true}
    $p=[pscustomobject]@{Id=10000+$global:fixtureStarts.Count;Handle=1;HasExited=$true;ExitCode=$(if($failure){1}else{0})}
    foreach($name in @('Refresh','WaitForExit','Dispose')){$p|Add-Member -MemberType ScriptMethod -Name $name -Value {}}
    return $p
}
function Start-Sleep {param($Seconds,$Milliseconds)}
& (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $base -OverlapFixedRf
$rf=Get-Content -LiteralPath (Join-Path $base 'control/status.json') -Raw|ConvertFrom-Json
$robust=Get-Content -LiteralPath (Join-Path $dro 'control/status.json') -Raw|ConvertFrom-Json
if($rf.Count -ne 10 -or $robust.tasks.Count -ne 10){throw 'Terminal task count mismatch'}
if($FailRf){
    if(@($rf|Where-Object state -eq 'FAILED').Count -ne 1 -or @($rf|Where-Object state -eq 'COMPLETE').Count -ne 9 -or
        $robust.state -ne 'FINISHED_WITH_FAILURES' -or @($robust.tasks|Where-Object state -eq 'BLOCKED_BASELINE').Count -ne 1 -or
        @($robust.tasks|Where-Object state -eq 'COMPLETE').Count -ne 9){throw 'Failed RF stopped unrelated DRO markets'}
}elseif(@($rf|Where-Object state -ne 'COMPLETE').Count -or $robust.state -ne 'FINISHED' -or
    @($robust.tasks|Where-Object state -ne 'COMPLETE').Count){throw 'Terminal result mismatch'}
if(($global:fixtureStarts.GetRange(0,4) -join ',') -ne 'market_008:RF,market_009:RF,market_000:RF_CHI2,market_001:RF_CHI2'){
    throw 'Idle slots did not immediately start ready DRO markets'
}
if($global:fixtureStarts.Count -ne 12){throw 'Completed RF markets were recomputed'}
$before=$global:fixtureStarts.Count
& (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $base -OverlapFixedRf -AdoptRunning
if($global:fixtureStarts.Count -ne $before){throw 'Recovery reset attempts or recomputed complete work'}
if(!$FailRf){
    # A controller restart must adopt a live DRO optimizer without launching it twice.
    $statusPath=Join-Path $dro 'control/status.json'
    $saved=Get-Content -LiteralPath $statusPath -Raw|ConvertFrom-Json
    $start=Get-Date
    $savedTask=@($saved.tasks|Where-Object market -eq 'market_009')[0]
    $savedTask.state='RUNNING';$savedTask.pid=49999;$savedTask.started=$start.ToString('o')
    $saved|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $statusPath -Encoding UTF8
    $global:fixtureLiveWorker=[pscustomobject]@{Id=49999;Handle=1;StartTime=$start;HasExited=$true;ExitCode=0}
    foreach($name in @('Refresh','WaitForExit','Dispose')){$global:fixtureLiveWorker|Add-Member -MemberType ScriptMethod -Name $name -Value {}}
    [OlistWindowsProcess]::FixtureCommand='"OlistBestCsaaDroRunner" "-Dolist.lambdaGrid=0.01,0.05,0.1,0.25,0.5,1,2,5,10" "-Dolist.threads=4" "-Dolist.limit=14400" "-Dolist.includeTrend=true" "-Dolist.fixedTrend104=true" "run" "'+$base+'" "'+$dro+'" "market_009" "RF"'
    $global:fixtureAdopting=$true
    & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $base -OverlapFixedRf -AdoptRunning
    $global:fixtureAdopting=$false
    if($global:fixtureStarts.Count -ne $before){throw 'Live DRO was duplicated on adoption'}
    if(-not((Get-Content -LiteralPath (Join-Path $base 'control/events.log')) -match 'ADOPT market_009 RF_CHI2 pid=49999')){throw 'Live DRO was not adopted'}
}
Write-Output "OLIST_OVERLAP_PASS shared_4_slots/per_market_dependency/reuse_8_RF/recovery fail_RF=$($FailRf.IsPresent) no_optimizers"
