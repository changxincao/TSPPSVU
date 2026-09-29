param(
    [Parameter(Mandatory = $true)]
    [string]$Replications,
    [Parameter(Mandatory = $true)]
    [string]$Group,
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'

$taskRoot = 'D:\ccx\TSPP_SVU\staging\w1-positive-support-medium-20260929'
$experimentRoot = 'D:\ccx\TSPP_SVU\experiments\moderate_common_seed20261020_20260929'
$inputRoot = Join-Path $experimentRoot 'medium_input'
$experiment1Root = Join-Path $experimentRoot 'medium_experiment1'
$statusFile = Join-Path $OutputRoot ("status_{0}.txt" -f $Group)
$logFile = Join-Path $OutputRoot ("controller_{0}.log" -f $Group)

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
"RUNNING group=$Group replications=$Replications started=$([DateTime]::Now.ToString('o')) radii=0.00025,0.001,0.01 support=full_training_lane_min_max taskParallel=1 solverThreads=4" |
    Set-Content -LiteralPath $statusFile -Encoding UTF8

$java = 'D:\Java\jdk-21\bin\java.exe'
$classpath = @(
    (Join-Path $taskRoot 'bin'),
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
    'C:\Windows\System32',
    'C:\Windows',
    'C:\Windows\System32\Wbem'
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $taskRoot

try {
    & $java "-Djava.library.path=$native" -cp $classpath `
        Test.analysis.synthetic.TRBSVUExperiment2IdeMain `
        "--input=$inputRoot" `
        "--experiment1-output=$experiment1Root" `
        "--output=$OutputRoot" `
        '--parallel=1' `
        '--solver-threads=4' `
        '--limit-seconds=14400' `
        "--replications=$Replications" `
        '--phase=primary' `
        '--methods=C-W1' `
        '--w1-grid=0.00025,0.001,0.01' *>&1 |
        Tee-Object -FilePath $logFile -Append | Out-Null

    $exitCode = if ($null -eq $LASTEXITCODE) { 1 } else { $LASTEXITCODE }
    if ($exitCode -eq 0) {
        "FINISHED exit=0 ended=$([DateTime]::Now.ToString('o'))" |
            Set-Content -LiteralPath $statusFile -Encoding UTF8
    } else {
        "FAILED exit=$exitCode ended=$([DateTime]::Now.ToString('o')) log=$logFile" |
            Set-Content -LiteralPath $statusFile -Encoding UTF8
    }
    exit $exitCode
} catch {
    "FAILED exception=$($_.Exception.Message) ended=$([DateTime]::Now.ToString('o')) log=$logFile" |
        Set-Content -LiteralPath $statusFile -Encoding UTF8
    throw
}
