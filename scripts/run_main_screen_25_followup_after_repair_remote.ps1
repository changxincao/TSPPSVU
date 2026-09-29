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
$repairStatus = Join-Path $ExperimentRoot 'experiment1_repair_status.txt'
$statusFile = Join-Path $ExperimentRoot 'followup_after_repair_status.txt'
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
        'dependency=experiment1_repair_status.txt:FINISHED'
        'parallelTasks=4'
        'solverThreads=4'
        $details
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

function Invoke-Java([string[]]$arguments) {
    & $java "-Djava.library.path=$native" -cp $classpath @arguments
    return $LASTEXITCODE
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

Write-Status 'WAITING_FOR_EXPERIMENT1_REPAIR' @(
    'queuedPhases=other-contextual-variants,C-Chi2'
)
$repairRetries = 0
while ($true) {
    if (Test-Path -LiteralPath $repairStatus -PathType Leaf) {
        $repairState = Get-Content -LiteralPath $repairStatus |
            Where-Object { $_ -like 'state=*' } | Select-Object -First 1
        if ($repairState -eq 'state=FINISHED') { break }
        if ($repairState -eq 'state=FAILED') {
            $repairRetries++
            if ($repairRetries -gt 5) {
                Write-Status 'BLOCKED_BY_EXPERIMENT1_REPAIR_FAILURE' @(
                    'queuedPhases=other-contextual-variants,C-Chi2'
                    'repairRetriesExhausted=5'
                )
                exit 2
            }
            Write-Status 'RETRYING_EXPERIMENT1_REPAIR' @(
                'queuedPhases=other-contextual-variants,C-Chi2'
                "repairAttempt=$repairRetries"
                'note=matching completed tasks are reused; only incomplete tasks solve again'
            )
            try {
                & (Join-Path $TaskRoot 'scripts\run_main_screen_25_repair_only_remote.ps1') `
                    -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot
            } catch {
                Write-Status 'WAITING_TO_RETRY_EXPERIMENT1_REPAIR' @(
                    'queuedPhases=other-contextual-variants,C-Chi2'
                    "repairAttempt=$repairRetries"
                    "lastError=$($_.Exception.Message)"
                )
                Start-Sleep -Seconds 30
            }
            continue
        }
    }
    Start-Sleep -Seconds 30
}

$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

Write-Status 'RUNNING_OTHER_CONTEXTUAL_VARIANTS' @(
    'methods=CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA'
    'note=matching CSAA-Tri complete markers are reused'
)
$exitCode = Invoke-Java @(
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
if ($exitCode -ne 0) {
    Write-Status 'BLOCKED_BY_CONTEXTUAL_VARIANT_FAILURE' @(
        "exitCode=$exitCode"
        'note=rerun this queue after repairing failed contextual tasks; valid complete markers are reused'
    )
    exit $exitCode
}

Write-Status 'RUNNING_CHI_SQUARED' @(
    'method=C-Chi2'
    'contextBase=replication-specific validation-selected CSAA-Tri bandwidth'
    'lambdaGrid=0.1,0.25,0.5,1,2'
)
$exitCode = Invoke-Java @(
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
if ($exitCode -ne 0) {
    Write-Status 'FAILED_CHI_SQUARED' @(
        "exitCode=$exitCode"
        'note=valid checkpoints and complete markers are reused on retry'
    )
    exit $exitCode
}

Write-Status 'FINISHED' @(
    'methods=CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA,C-Chi2'
)
