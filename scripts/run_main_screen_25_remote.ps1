param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot
)

$ErrorActionPreference = 'Stop'
$inputRoot = Join-Path $ExperimentRoot 'input'
$experiment1Root = Join-Path $ExperimentRoot 'experiment1'
$experiment2Root = Join-Path $ExperimentRoot 'experiment2'
$statusFile = Join-Path $ExperimentRoot 'batch_status.txt'
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

New-Item -ItemType Directory -Force -Path $ExperimentRoot | Out-Null
$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

try {
    if (-not (Test-Path -LiteralPath (Join-Path $inputRoot 'batch_manifest.tsv'))) {
        & $java "-Djava.library.path=$native" -cp $classpath `
            Test.analysis.synthetic.TRBSVUGenerateModerateCommonCasesMain `
            $inputRoot MEDIUM 436191 25
        if ($LASTEXITCODE -ne 0) {
            throw "Remote generator failed with exit code $LASTEXITCODE"
        }
    }

    @(
        'state=RUNNING_EXPERIMENT1'
        "started=$([DateTime]::Now.ToString('o'))"
        'batchSeed=436191'
        'replications=0-24'
        'queriesPerReplication=40'
        'methodsExperiment1=D,SAA-All,CSAA-Tri'
        'methodExperiment2=C-Chi2'
        'lambdaGrid=0.1,0.25,0.5,1,2'
        'parallelTasks=4'
        'solverThreads=4'
        'limitSecondsPerSolve=14400'
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8

    & $java "-Djava.library.path=$native" -cp $classpath `
        Test.analysis.synthetic.TRBSVUExperiment1IdeMain `
        "--input=$inputRoot" `
        "--output=$experiment1Root" `
        '--parallel=4' `
        '--solver-threads=4' `
        '--limit-seconds=14400' `
        '--validation-origins=25' `
        '--replications=0-24' `
        '--methods=D,SAA-All,CSAA-Tri'
    if ($LASTEXITCODE -ne 0) {
        throw "Experiment 1 scheduler failed with exit code $LASTEXITCODE"
    }

    @(
        'state=RUNNING_CHI_SQUARED'
        "experiment1Finished=$([DateTime]::Now.ToString('o'))"
        'replications=0-24'
        'lambdaGrid=0.1,0.25,0.5,1,2'
        'parallelTasks=4'
        'solverThreads=4'
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8

    & $java "-Djava.library.path=$native" -cp $classpath `
        Test.analysis.synthetic.TRBSVUExperiment2IdeMain `
        "--input=$inputRoot" `
        "--experiment1-output=$experiment1Root" `
        "--output=$experiment2Root" `
        '--parallel=4' `
        '--solver-threads=4' `
        '--limit-seconds=14400' `
        '--replications=0-24' `
        '--phase=primary' `
        '--methods=C-Chi2' `
        '--lambda-grid=0.1,0.25,0.5,1,2'
    if ($LASTEXITCODE -ne 0) {
        throw "C-Chi2 scheduler failed with exit code $LASTEXITCODE"
    }

    @(
        'state=FINISHED'
        "ended=$([DateTime]::Now.ToString('o'))"
        'replications=0-24'
        'lambdaGrid=0.1,0.25,0.5,1,2'
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
} catch {
    @(
        'state=FAILED'
        "ended=$([DateTime]::Now.ToString('o'))"
        "message=$($_.Exception.Message)"
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
    throw
}
