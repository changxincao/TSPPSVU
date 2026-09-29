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
$statusFile = Join-Path $ExperimentRoot 'chi_after_repair_status.txt'
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
        'method=C-Chi2'
        'lambdaGrid=0.1,0.25,0.5,1,2'
        'parallelTasks=4'
        'solverThreads=4'
        $details
    ) | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

if (-not (Test-Path -LiteralPath (Join-Path $inputRoot 'batch_manifest.tsv') -PathType Leaf)) {
    throw "Missing frozen 25-replication input: $inputRoot"
}

Write-Status 'WAITING_FOR_EXPERIMENT1_REPAIR' @(
    'note=no other CSAA family is included in this queued job'
)

while ($true) {
    if (Test-Path -LiteralPath $repairStatus -PathType Leaf) {
        $repairState = Get-Content -LiteralPath $repairStatus |
            Where-Object { $_ -like 'state=*' } | Select-Object -First 1
        if ($repairState -eq 'state=FINISHED') { break }
        if ($repairState -eq 'state=FAILED') {
            Write-Status 'BLOCKED_BY_EXPERIMENT1_REPAIR_FAILURE' @(
                'note=repair must be resumed successfully before C-Chi2 can start'
            )
            exit 2
        }
    }
    Start-Sleep -Seconds 30
}

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($replication in 0..24) {
    foreach ($method in @('D', 'SAA-All', 'CSAA-Tri')) {
        $complete = Join-Path $experiment1Root `
            ("rep_{0:D3}\{1}\complete.txt" -f $replication, $method)
        if (-not (Test-Path -LiteralPath $complete -PathType Leaf)) {
            $missing.Add(("rep_{0:D3}/{1}" -f $replication, $method))
        }
    }
}
if ($missing.Count -ne 0) {
    Write-Status 'BLOCKED_BY_INCOMPLETE_EXPERIMENT1' @(
        "missing=$($missing -join ',')"
    )
    exit 3
}

$env:Path = @(
    'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    'D:\ccx\TSPP_SVU\lib',
    'D:\Java\jdk-21\bin',
    $env:Path
) -join ';'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

Write-Status 'RUNNING_CHI_SQUARED' @(
    'contextBase=replication-specific validation-selected CSAA-Tri bandwidth'
)

try {
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
    Write-Status 'FINISHED' @(
        'contextBase=replication-specific validation-selected CSAA-Tri bandwidth'
    )
} catch {
    Write-Status 'FAILED' @(
        "message=$($_.Exception.Message)"
    )
    throw
}
