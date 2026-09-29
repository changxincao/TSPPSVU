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
$statusFile = Join-Path $ExperimentRoot 'followup_status.txt'
$phaseResults = Join-Path $ExperimentRoot 'followup_phase_results.tsv'
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

function Write-Status([string]$state, [string[]]$details) {
    @(
        "state=$state"
        "updated=$([DateTime]::Now.ToString('o'))"
        'replications=0-24'
        'queriesPerReplication=40'
        'parallelTasks=4'
        'solverThreads=4'
        'limitSecondsPerSolve=14400'
        $details
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

function Invoke-Phase([string]$name, [string[]]$arguments) {
    $started = [DateTime]::Now
    & $java "-Djava.library.path=$native" -cp $classpath @arguments | Out-Host
    $exitCode = $LASTEXITCODE
    $ended = [DateTime]::Now
    @(
        $name,
        $started.ToString('o'),
        $ended.ToString('o'),
        $exitCode
    ) -join "`t" | Add-Content -LiteralPath $phaseResults -Encoding UTF8
    return $exitCode
}

if (-not (Test-Path -LiteralPath (Join-Path $inputRoot 'batch_manifest.tsv') -PathType Leaf)) {
    throw "Missing frozen 25-replication input: $inputRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $TaskRoot 'analysis\trb_svu\rf_leaf_weights.py') -PathType Leaf)) {
    throw "Missing RF helper under task root: $TaskRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $TaskRoot '.venv-rsome\Scripts\python.exe') -PathType Leaf)) {
    throw "Missing RF Python environment under task root: $TaskRoot"
}

$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

"phase`tstarted`tended`texitCode" | Set-Content -LiteralPath $phaseResults -Encoding UTF8
$failedPhases = [System.Collections.Generic.List[string]]::new()

Write-Status 'REPAIRING_BASELINES' @(
    'methods=D,SAA-All'
    'note=matching complete markers are reused; only missing or failed tasks solve again'
)
$exit = Invoke-Phase 'baseline-repair' @(
    'Test.analysis.synthetic.TRBSVUExperiment1IdeMain',
    "--input=$inputRoot",
    "--output=$experiment1Root",
    '--parallel=4',
    '--solver-threads=4',
    '--limit-seconds=14400',
    '--validation-origins=25',
    '--replications=0-24',
    '--methods=D,SAA-All'
)
if ($exit -ne 0) { $failedPhases.Add("baseline-repair:$exit") }

Write-Status 'RUNNING_ALL_CONTEXTUAL_VARIANTS' @(
    'methods=CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA'
    'bandwidthGrid=0.1,0.25,0.5,0.8,0.9,1,2,3,5,10,30,50,100'
    'rfMinLeafGrid=1,2,5,10'
    'note=existing matching CSAA-Tri results are reused; all five families are aggregated after completion'
)
$exit = Invoke-Phase 'all-contextual-variants' @(
    'Test.analysis.synthetic.TRBSVUExperiment1IdeMain',
    "--input=$inputRoot",
    "--output=$experiment1Root",
    '--parallel=4',
    '--solver-threads=4',
    '--limit-seconds=14400',
    '--validation-origins=25',
    '--replications=0-24',
    '--methods=CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA'
)
if ($exit -ne 0) { $failedPhases.Add("all-contextual-variants:$exit") }

Write-Status 'RUNNING_CHI_SQUARED' @(
    'method=C-Chi2'
    'contextBase=replication-specific validation-selected CSAA-Tri bandwidth'
    'lambdaGrid=0.1,0.25,0.5,1,2'
)
$exit = Invoke-Phase 'conditional-chi-squared' @(
    'Test.analysis.synthetic.TRBSVUExperiment2IdeMain',
    "--input=$inputRoot",
    "--experiment1-output=$experiment1Root",
    "--output=$experiment2Root",
    '--parallel=4',
    '--solver-threads=4',
    '--limit-seconds=14400',
    '--replications=0-24',
    '--phase=primary',
    '--methods=C-Chi2',
    '--lambda-grid=0.1,0.25,0.5,1,2'
)
if ($exit -ne 0) { $failedPhases.Add("conditional-chi-squared:$exit") }

if ($failedPhases.Count -eq 0) {
    Write-Status 'FINISHED' @(
        'methods=CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA,C-Chi2'
        'chiSquaredContextBase=CSAA-Tri'
    )
} else {
    Write-Status 'FINISHED_WITH_FAILURES' @(
        "failedPhases=$($failedPhases -join ',')"
        'note=rerun the same follow-up launcher; complete markers prevent recomputation'
    )
    throw "Follow-up finished with failed phases: $($failedPhases -join ',')"
}
