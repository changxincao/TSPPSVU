$ErrorActionPreference = 'Stop'

$taskRoot = 'D:\ccx\TSPP_SVU\staging\d8830e9-w1-medium-moderate-20260929'
$experimentRoot = 'D:\ccx\TSPP_SVU\experiments\moderate_common_seed20261020_20260929'
$inputRoot = Join-Path $experimentRoot 'medium_input'
$experiment1Root = Join-Path $experimentRoot 'medium_experiment1'
$outputRoot = Join-Path $experimentRoot 'medium_w1_top_radii_d8830e9'
$statusFile = Join-Path $outputRoot 'status.txt'
$logFile = Join-Path $outputRoot 'controller.log'

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
"RUNNING started=$([DateTime]::Now.ToString('o')) radii=0.00025,0.001,0.01 support=empirical_lane_min_max parallel=1 solverThreads=4" |
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
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $taskRoot

try {
    & $java "-Djava.library.path=$native" -cp $classpath `
        Test.analysis.synthetic.TRBSVUExperiment2IdeMain `
        "--input=$inputRoot" `
        "--experiment1-output=$experiment1Root" `
        "--output=$outputRoot" `
        '--parallel=1' `
        '--solver-threads=4' `
        '--limit-seconds=14400' `
        '--replications=0-4' `
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
