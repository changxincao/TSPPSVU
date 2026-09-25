param(
    [Parameter(Mandatory=$true)][string]$Deploy,
    [Parameter(Mandatory=$true)][string]$InputRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [int[]]$Replications = @(0,1,2),
    [string]$Candidates = '0.6,0.7,0.8,0.9',
    [int]$SolverThreads = 4,
    [int]$LimitSeconds = 14400
)

$ErrorActionPreference = 'Stop'
$cp = "bin;D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\ccx\TSPP_SVU\lib\mosek.jar"
$kernels = @('EPANECHNIKOV','TRIANGULAR')
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
Set-Location -LiteralPath $Deploy
$env:PATH = 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;' + $env:PATH

foreach ($replication in $Replications) {
    $repName = 'rep_{0:D3}' -f $replication
    $instance = Join-Path $InputRoot "$repName\queries\query_000.instance.tsv"
    if (-not (Test-Path -LiteralPath $instance)) { throw "Missing instance: $instance" }
    $processes = @()
    foreach ($kernel in $kernels) {
        $taskDir = Join-Path $OutputRoot "$repName\$kernel"
        New-Item -ItemType Directory -Force -Path $taskDir | Out-Null
        $result = Join-Path $taskDir 'validation.tsv'
        $stdout = Join-Path $taskDir 'task.log'
        $stderr = Join-Path $taskDir 'task.err.log'
        $arguments = @('-cp', $cp, 'Test.analysis.synthetic.TRBSVUBandwidthValidationPilot',
            $instance, $result, $kernel, $Candidates,
            $SolverThreads.ToString(), $LimitSeconds.ToString())
        $processes += Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $Deploy `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    }
    $processes | Wait-Process
    foreach ($process in $processes) {
        if ($process.ExitCode -ne 0) { throw "$repName validation task failed with exit $($process.ExitCode)." }
    }
}

Set-Content -LiteralPath (Join-Path $OutputRoot 'complete.txt') `
    -Value ("completed=" + (Get-Date -Format o) + "`ncandidates=" + $Candidates)
