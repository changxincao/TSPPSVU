$ErrorActionPreference = 'Stop'

$taskRoot = 'D:\ccx\TSPP_SVU\staging\88de8ab-main-screen-25-20260929'
$experimentRoot = 'D:\ccx\TSPP_SVU\experiments\main_screen_25_medium_moderate_seed436191_20260929'
$status = Join-Path $experimentRoot 'rf_csaa_status.txt'
$log = Join-Path $experimentRoot 'control\rf_csaa_serial_20260930.log'
$java = 'D:\Java\jdk-21\bin\java.exe'
$cplexBin = 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64'
$classpath = @(
    (Join-Path $taskRoot 'bin'),
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar',
    'D:\ccx\TSPP_SVU\lib\mosek.jar'
) -join ';'
$nativePath = @($cplexBin, 'D:\ccx\TSPP_SVU\lib') -join ';'

@(
    'state=RUNNING',
    'started=' + [DateTime]::Now.ToString('o'),
    'methods=RF-CSAA',
    'parallelTasks=1',
    'solverThreads=4',
    'python=' + (Join-Path $taskRoot '.venv-rsome\Scripts\python.exe'),
    'rfTrees=500',
    'note=serial RF task avoids concurrent scipy/sklearn imports; existing checkpoints reused'
) | Set-Content -LiteralPath $status -Encoding UTF8

$env:Path = @($cplexBin, 'D:\ccx\TSPP_SVU\lib', 'D:\Java\jdk-21\bin', $env:Path) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $taskRoot

& $java "-Djava.library.path=$nativePath" -cp $classpath `
    Test.analysis.synthetic.TRBSVUExperiment1IdeMain `
    "--input=$(Join-Path $experimentRoot 'input')" `
    "--output=$(Join-Path $experimentRoot 'experiment1')" `
    '--parallel=1' `
    '--solver-threads=4' `
    '--limit-seconds=14400' `
    '--validation-origins=25' `
    '--replications=0-24' `
    '--methods=RF-CSAA' *>> $log

$exitCode = $LASTEXITCODE
@(
    'state=' + $(if ($exitCode -eq 0) { 'FINISHED' } else { 'FAILED' }),
    'ended=' + [DateTime]::Now.ToString('o'),
    'exitCode=' + $exitCode,
    'methods=RF-CSAA'
) | Set-Content -LiteralPath $status -Encoding UTF8
exit $exitCode
