param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot
)

$ErrorActionPreference = 'Stop'
$java = 'D:\Java\jdk-21\bin\java.exe'
$classpath = @(
    (Join-Path $TaskRoot 'bin'),
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar',
    'D:\ccx\TSPP_SVU\lib\mosek.jar'
) -join ';'
$native = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib'
) -join ';'
$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'

$log = Join-Path $ExperimentRoot 'exp_rf_retry.log'
$status = Join-Path $ExperimentRoot 'exp_rf_retry_status.txt'
@(
    'state=RUNNING'
    "started=$([DateTime]::Now.ToString('o'))"
    'methods=CSAA-Exp,RF-CSAA'
    'parallelTasks=2'
    'solverThreads=4'
    'javaClassVersion=65'
) | Set-Content -LiteralPath $status -Encoding UTF8

& $java "-Djava.library.path=$native" -cp $classpath `
    Test.analysis.synthetic.TRBSVUExperiment1IdeMain `
    "--input=$(Join-Path $ExperimentRoot 'input')" `
    "--output=$(Join-Path $ExperimentRoot 'experiment1')" `
    '--parallel=2' '--solver-threads=4' '--limit-seconds=14400' `
    '--validation-origins=25' '--replications=0-24' `
    '--methods=CSAA-Exp,RF-CSAA' *>&1 | Tee-Object -LiteralPath $log
$exitCode = $LASTEXITCODE
@(
    "state=$(if ($exitCode -eq 0) { 'FINISHED' } else { 'FAILED' })"
    "finished=$([DateTime]::Now.ToString('o'))"
    "exitCode=$exitCode"
) | Set-Content -LiteralPath $status -Encoding UTF8
exit $exitCode
