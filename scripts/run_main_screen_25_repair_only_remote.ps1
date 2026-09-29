param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot
)

$ErrorActionPreference = 'Stop'
$inputRoot = Join-Path $ExperimentRoot 'input'
$experiment1Root = Join-Path $ExperimentRoot 'experiment1'
$statusFile = Join-Path $ExperimentRoot 'experiment1_repair_status.txt'
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

if (-not (Test-Path -LiteralPath (Join-Path $inputRoot 'batch_manifest.tsv') -PathType Leaf)) {
    throw "Missing frozen 25-replication input: $inputRoot"
}

$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

@(
    'state=RUNNING_EXPERIMENT1_REPAIR_ONLY'
    "started=$([DateTime]::Now.ToString('o'))"
    'replications=0-24'
    'methods=SAA-All,CSAA-Tri'
    'parallelTasks=2'
    'solverThreads=4'
    'note=matching completed tasks are skipped; no other CSAA family or DRO is launched'
) | Set-Content -LiteralPath $statusFile -Encoding UTF8

try {
    & $java "-Djava.library.path=$native" -cp $classpath `
        Test.analysis.synthetic.TRBSVUExperiment1IdeMain `
        "--input=$inputRoot" `
        "--output=$experiment1Root" `
        '--parallel=2' `
        '--solver-threads=4' `
        '--limit-seconds=14400' `
        '--validation-origins=25' `
        '--replications=0-24' `
        '--methods=SAA-All,CSAA-Tri'
    if ($LASTEXITCODE -ne 0) {
        throw "Experiment 1 repair scheduler failed with exit code $LASTEXITCODE"
    }
    @(
        'state=FINISHED'
        "ended=$([DateTime]::Now.ToString('o'))"
        'methods=SAA-All,CSAA-Tri'
        'note=no other CSAA family or DRO was launched'
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
} catch {
    @(
        'state=FAILED'
        "ended=$([DateTime]::Now.ToString('o'))"
        "message=$($_.Exception.Message)"
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
    throw
}
