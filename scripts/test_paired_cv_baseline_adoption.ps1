param([Parameter(Mandatory=$true)][string]$TestRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $TestRoot){throw 'Use a fresh isolated fixture directory'}
$TestRoot=[IO.Path]::GetFullPath($TestRoot)
$taskRoot=Join-Path $TestRoot 'runtime_root'
$experiment=Join-Path $TestRoot 'experiment'
New-Item -ItemType Directory -Force -Path $taskRoot,(Join-Path $experiment 'cv030050/input/rep_000/instance')|Out-Null
@('caseSeed=20261025','cvLower=0.3','cvUpper=0.5','queries=40','OOS=1000')|
    Set-Content -LiteralPath (Join-Path $experiment 'cv030050/input/rep_000/instance/manifest.txt')
Add-Type 'public static class OlistWindowsProcess { public static string FixtureCommand=""; public static string CommandLine(int pid) { return FixtureCommand; } }'
$target=Join-Path $experiment 'cv030050/experiment1/rep_000/CSAA-Exp'
$target=$target.Replace('/','\')
[OlistWindowsProcess]::FixtureCommand='Test.analysis.synthetic.TRBSVUExperiment1IdeMain "'+$target+'"'
$global:baselineFixtureWorker=[pscustomobject]@{Id=42;Path=(Join-Path $taskRoot 'runtime/java/bin/java.exe').Replace('/','\');Handle=1;HasExited=$false;ExitCode=0}
foreach($name in @('Refresh','WaitForExit','Dispose')){$global:baselineFixtureWorker|Add-Member -MemberType ScriptMethod -Name $name -Value {}}
$global:baselineFixtureStarts=[Collections.Generic.List[string]]::new()
function Get-Process {param($Name,$ErrorAction);return $global:baselineFixtureWorker}
function Write-FixtureComplete($Target){
    New-Item -ItemType Directory -Force -Path $Target|Out-Null
    Set-Content -LiteralPath (Join-Path $Target 'complete.txt') 'fixture-java-audited'
    foreach($q in 0..39){
        foreach($relative in @('query_metadata.txt','validation/summary.csv','validation/details.csv','solve/final_solve.csv','solve/final_weights.csv','oos/summary.csv','oos/draws.csv')){
            $file=Join-Path $Target ('queries/query_{0:D3}/{1}' -f $q,$relative)
            New-Item -ItemType Directory -Force -Path (Split-Path $file -Parent)|Out-Null
            Set-Content -LiteralPath $file 'fixture-complete'
        }
    }
}
Write-FixtureComplete $target
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    if($running.Count -ge 4){throw 'Shared parallel cap exceeded'}
    $lockRejected=$false
    try{$duplicate=[IO.File]::Open((Join-Path $experiment 'control/baseline_cv030050/scheduler.lock'),'OpenOrCreate','ReadWrite','None');$duplicate.Dispose()}
    catch [IO.IOException]{$lockRejected=$true}
    if(!$lockRejected){throw 'Controller lock is not held'}
    if($ArgumentList -notmatch '25 4 14400$'){throw 'Origin/thread/limit settings changed'}
    $output=Split-Path $RedirectStandardOutput -Parent
    $method=Split-Path $output -Leaf
    if($method -eq 'CSAA-Exp'){throw 'Adopted worker relaunched'}
    $global:baselineFixtureStarts.Add($method)
    $fail=$method -eq 'RF-CSAA' -and @($global:baselineFixtureStarts|Where-Object {$_ -eq 'RF-CSAA'}).Count -eq 1
    if(!$fail){Write-FixtureComplete $output}
    $process=[pscustomobject]@{Id=100+$global:baselineFixtureStarts.Count;Handle=1;HasExited=$true;ExitCode=$(if($fail){1}else{0})}
    foreach($name in @('Refresh','WaitForExit','Dispose')){$process|Add-Member -MemberType ScriptMethod -Name $name -Value {}}
    return $process
}
function Start-Sleep {param($Seconds,$Milliseconds);$global:baselineFixtureWorker.HasExited=$true}
$before=Get-Location
try{
    & (Join-Path $PSScriptRoot 'run_paired_cv_extension_baselines_remote.ps1') -TaskRoot $taskRoot -ExperimentRoot $experiment -Cell cv030050 -ReplicationCount 1 -MaxParallel 4 -SkipCompleted
    $state=Get-Content -LiteralPath (Join-Path $experiment 'control/baseline_cv030050/status.json') -Raw|ConvertFrom-Json
    if($state.state -ne 'FINISHED' -or $global:baselineFixtureStarts.Count -ne 5){throw 'Adoption/retry/task completion mismatch'}
    $events=Import-Csv -LiteralPath (Join-Path $experiment 'control/baseline_cv030050/events.csv')
    if(@($events|Where-Object state -eq 'ADOPTED').Count -ne 1){throw 'Worker was not adopted'}
    $probe=[IO.File]::Open((Join-Path $experiment 'control/baseline_cv030050/scheduler.lock'),'OpenOrCreate','ReadWrite','None');$probe.Dispose()
    'BASELINE_ADOPTION_PASS: controller lock, live adoption, four slots, retry, no native solvers'
}finally{Set-Location $before}
