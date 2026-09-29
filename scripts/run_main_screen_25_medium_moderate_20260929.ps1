param(
    [string]$Root = ''
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Join-Path $workspace 'analysis_runs\main_screen_25_medium_moderate_20260929'
}
$inputRoot = Join-Path $Root 'input'
$experiment1Root = Join-Path $Root 'experiment1'
$experiment2Root = Join-Path $Root 'experiment2'
$statusFile = Join-Path $Root 'batch_status.txt'
$java = 'D:\软件\Java\jdk_22\bin\java.exe'
$classpath = @(
    (Join-Path $workspace 'bin'),
    'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar',
    'D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'
) -join ';'
$native = @(
    'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\软件\Mosek\11.0\tools\platform\win64x86\bin'
) -join ';'

if (-not (Test-Path -LiteralPath (Join-Path $inputRoot 'batch_manifest.tsv'))) {
    throw "Missing frozen 25-case input: $inputRoot"
}

New-Item -ItemType Directory -Force -Path $Root | Out-Null
$env:Path = @(
    'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\软件\Mosek\11.0\tools\platform\win64x86\bin',
    'D:\软件\Java\jdk_22\bin',
    $env:Path
) -join ';'
Set-Location -LiteralPath $workspace

@(
    'state=RUNNING_EXPERIMENT1'
    "started=$([DateTime]::Now.ToString('o'))"
    'replications=0-24'
    'queriesPerReplication=40'
    'methodsExperiment1=D,SAA-All,CSAA-Tri'
    'methodExperiment2=C-Chi2'
    'lambdaGrid=0.1,0.25,0.5,1,2'
    'parallelTasks=4'
    'solverThreads=4'
    'limitSecondsPerSolve=14400'
) | Set-Content -LiteralPath $statusFile -Encoding UTF8

try {
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
