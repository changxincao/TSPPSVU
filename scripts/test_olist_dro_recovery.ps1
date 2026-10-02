param([Parameter(Mandatory=$true)][string]$TestRoot)
$ErrorActionPreference='Stop'
$TestRoot=[IO.Path]::GetFullPath($TestRoot)
if(Test-Path -LiteralPath $TestRoot){throw 'Use a fresh isolated fixture directory'}
New-Item -ItemType Directory -Path $TestRoot|Out-Null
# Exercise the real scheduler with a fake Java command: no optimizer or live experiment touched.
$fake=Join-Path $TestRoot 'fake_java.ps1'
@'
$tokens=@($args)
if($tokens -contains 'selfcheck'){$global:LASTEXITCODE=0;return}
$modeIndex=[Array]::IndexOf($tokens,'select')
if($modeIndex -ge 0){
    $out=$tokens[$modeIndex+2]
    "method`tselection`nRF`tUSER_FIXED_RF"|Set-Content -LiteralPath (Join-Path $out 'global_selection.tsv')
    $global:LASTEXITCODE=0;return
}
if($tokens -contains 'audit'){
    $global:LASTEXITCODE=0;return
}
throw 'Unexpected Java command: would invoke an optimizer'
'@ | Set-Content -LiteralPath $fake -Encoding UTF8
foreach($p in @('base/inputs','base/control','native','lib','out/runtime/classes/Test/analysis/brazil')){
    New-Item -ItemType Directory -Force -Path (Join-Path $TestRoot $p)|Out-Null
}
foreach($p in @('lib/cplex.jar','lib/mosek.jar','lib/mosek64_11_0.dll','native/cplex2211.dll','out/runtime/classes/Test/analysis/brazil/OlistBestCsaaDroRunner.class')){
    [IO.File]::WriteAllText((Join-Path $TestRoot $p),'fixture')
}
$base=Join-Path $TestRoot 'base';$out=Join-Path $TestRoot 'out'
[pscustomobject]@{java=$fake;cplexJar=(Join-Path $TestRoot 'lib/cplex.jar');mosekJar=(Join-Path $TestRoot 'lib/mosek.jar');
    cplexNative=(Join-Path $TestRoot 'native');methods=@('RF');includeTrend=$true;fixedTrend104=$true}|
    ConvertTo-Json|Set-Content -LiteralPath (Join-Path $base 'config.json') -Encoding UTF8
"market`tmarket_seed`trf_seed`tinstance`tsha256`nmarket_000`t1`t1`ta`tx`nmarket_001`t2`t1`tb`ty`nmarket_002`t3`t1`tc`tz"|
    Set-Content -LiteralPath (Join-Path $base 'inputs/markets.tsv') -Encoding UTF8
$tasks=@(0..2|ForEach-Object {[pscustomobject]@{market=('market_{0:000}' -f $_);method='RF';state='COMPLETE'}})
function Save-Base {$tasks|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $base 'control/status.json') -Encoding UTF8}
function Run-Check {
    & (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1') -BaseRoot $base -Root $out -FixedRf -LambdaGrid '0.01,0.05,0.1,0.25,0.5,1,2,5,10'
    return (Get-Content -LiteralPath (Join-Path $out 'control/status.json') -Raw|ConvertFrom-Json)
}
Save-Base
$state=Run-Check
if($state.state -ne 'FINISHED' -or $state.tasks.Count -ne 3 -or @($state.tasks|Where-Object state -ne 'COMPLETE').Count){throw 'All-reused status regression'}
$tasks[1].state='FAILED';Save-Base
$state=Run-Check
if($state.state -ne 'FINISHED_WITH_FAILURES' -or @($state.tasks|Where-Object state -eq 'COMPLETE').Count -ne 2 -or
    @($state.tasks|Where-Object state -eq 'BLOCKED_BASELINE').Count -ne 1){throw 'Failed RF blocked other markets'}
$tasks[1].state='COMPLETE';Save-Base
$state=Run-Check
if($state.state -ne 'FINISHED' -or @($state.tasks|Where-Object state -eq 'COMPLETE').Count -ne 3){throw 'Repaired RF did not resume'}
Write-Output 'DRO_SCHEDULER_RECOVERY_PASS all_reused/one_RF_failed/repaired_RF no_optimizers'
