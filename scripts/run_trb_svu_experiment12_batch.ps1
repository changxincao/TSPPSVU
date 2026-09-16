param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/100_svu_experiment12_baseline_cases_20260915",
    [long]$BaseSeed = 20260915,
    [int]$Replications = 20,
    [int]$MaxParallel = 6,
    [int]$SolverThreads = 4,
    [int]$LimitSeconds = 14400,
    [int]$MaxAttempts = 2
)

$ErrorActionPreference = "Stop"
if ($Replications -lt 1 -or $MaxParallel -lt 1 -or $SolverThreads -lt 1 `
        -or $LimitSeconds -lt 1 -or $MaxAttempts -lt 1) {
    throw "All batch counts and limits must be positive."
}

$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$root = [System.IO.Path]::GetFullPath((Join-Path $project $OutputRoot))
$java = (Get-Command java).Source
$javac = (Get-Command javac).Source
$compileRoot = Join-Path $root "batch_classes"
$solverLibraries = [string]::Join(";", @(
    "D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar",
    "D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar"
))
New-Item -ItemType Directory -Force -Path $compileRoot | Out-Null
& $javac -encoding UTF-8 -Xprefer:source -cp "$compileRoot;$(Join-Path $project 'bin');$solverLibraries" `
    -sourcepath (Join-Path $project "src") -d $compileRoot `
    (Join-Path $project "src\Test\analysis\synthetic\TRBSVUExperiment12Main.java")
if ($LASTEXITCODE -ne 0) { throw "Experiment 1/2 compilation failed." }
$classpath = [string]::Join(";", @(
    $compileRoot,
    (Join-Path $project "bin"),
    $solverLibraries
))
$javaSourceSha256 = (& $java -cp $classpath `
    "Test.analysis.synthetic.TRBSVUExperiment12Main" "--source-fingerprint" `
    (Join-Path $project "src")).Trim()
if ($LASTEXITCODE -ne 0 -or $javaSourceSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Cannot compute the Java source fingerprint."
}
$rfPython = Join-Path $project ".venv-rsome\Scripts\python.exe"
$rfScriptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath `
    (Join-Path $project "analysis\trb_svu\rf_leaf_weights.py")).Hash.ToLowerInvariant()
$pcmScriptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath `
    (Join-Path $project "analysis\trb_svu\solve_pcm.py")).Hash.ToLowerInvariant()
$environmentCode = "import sys; from importlib.metadata import version; print('python='+sys.version.split()[0]+'|numpy='+version('numpy')+'|scikit-learn='+version('scikit-learn')+'|rsome='+version('rsome')+'|Mosek='+version('Mosek'))"
$pythonEnvironment = (& $rfPython -c $environmentCode).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pythonEnvironment)) {
    throw "Cannot identify the frozen Python environment."
}
$native = "D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64"
$logRoot = Join-Path $root "batch_logs"
$statusFile = Join-Path $root "batch_status.tsv"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

function Test-CurrentCompletion([string]$Path, [int]$Replication) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $instance = Join-Path $root ("rep_{0:D3}\instance\instance.tsv" -f $Replication)
    if (-not (Test-Path -LiteralPath $instance)) { return $false }
    $currentInstanceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $instance).Hash.ToLowerInvariant()
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) { $values[$parts[0]] = $parts[1] }
    }
    return $values.protocolVersion -eq 'TRBSVU_EXP12_V3' `
        -and $values.baseSeed -eq [string]$BaseSeed `
        -and $values.replication -eq [string]$Replication `
        -and $values.algorithm -eq 'compact' `
        -and $values.validationOrigins -eq '30' `
        -and $values.threads -eq [string]$SolverThreads `
        -and $values.limitSeconds -eq [string]$LimitSeconds `
        -and $values.instanceSha256 -eq $currentInstanceSha256 `
        -and $values.javaSourceSha256 -eq $javaSourceSha256 `
        -and $values.rfScriptSha256 -eq $rfScriptSha256 `
        -and $values.pcmScriptSha256 -eq $pcmScriptSha256 `
        -and $values.pythonEnvironment -eq $pythonEnvironment
}

$attempts = @{}
$pending = [System.Collections.Generic.Queue[int]]::new()
for ($rep = 0; $rep -lt $Replications; $rep++) {
    $completion = Join-Path $root ("rep_{0:D3}\experiment12_complete.txt" -f $rep)
    if (-not (Test-CurrentCompletion $completion $rep)) {
        $pending.Enqueue($rep)
        $attempts[$rep] = 0
    }
}

$rows = [System.Collections.Generic.List[object]]::new()
$running = [System.Collections.Generic.List[object]]::new()
while ($pending.Count -gt 0 -or $running.Count -gt 0) {
    while ($pending.Count -gt 0 -and $running.Count -lt $MaxParallel) {
        $rep = $pending.Dequeue()
        $attempts[$rep]++
        $attempt = $attempts[$rep]
        $stdout = Join-Path $logRoot ("rep_{0:D3}_attempt_{1}.out.log" -f $rep, $attempt)
        $stderr = Join-Path $logRoot ("rep_{0:D3}_attempt_{1}.err.log" -f $rep, $attempt)
        $arguments = @(
            "-Djava.library.path=$native", "-cp", $classpath,
            "Test.analysis.synthetic.TRBSVUExperiment12Main",
            "both", $rep, $BaseSeed, "compact", $root, 30, $SolverThreads, $LimitSeconds
        )
        $process = Start-Process -FilePath $java -ArgumentList $arguments `
            -WorkingDirectory $project -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
        $running.Add([pscustomobject]@{
            Replication = $rep
            Attempt = $attempt
            Process = $process
            Started = Get-Date
            Stdout = $stdout
            Stderr = $stderr
        })
    }

    for ($i = $running.Count - 1; $i -ge 0; $i--) {
        $job = $running[$i]
        if (-not $job.Process.HasExited) { continue }
        $job.Process.WaitForExit()
        $completion = Join-Path $root ("rep_{0:D3}\experiment12_complete.txt" -f $job.Replication)
        $success = $job.Process.ExitCode -eq 0 `
            -and (Test-CurrentCompletion $completion $job.Replication)
        $rows.Add([pscustomobject]@{
            replication = $job.Replication
            attempt = $job.Attempt
            exit_code = $job.Process.ExitCode
            success = $success
            elapsed_seconds = [math]::Round(((Get-Date) - $job.Started).TotalSeconds, 3)
            stdout = $job.Stdout
            stderr = $job.Stderr
        })
        $running.RemoveAt($i)
        if (-not $success -and $attempts[$job.Replication] -lt $MaxAttempts) {
            $pending.Enqueue($job.Replication)
        }
        $rows | Sort-Object replication, attempt | Export-Csv -Delimiter "`t" `
            -NoTypeInformation -Encoding UTF8 -LiteralPath $statusFile
    }
    if ($running.Count -gt 0) { Start-Sleep -Seconds 2 }
}

$failed = $rows | Group-Object replication | Where-Object { -not $_.Group[-1].success }
if ($failed.Count -gt 0) {
    throw "$($failed.Count) replication(s) failed after the configured attempts. See $statusFile"
}
Write-Output "All requested Experiment 1/2 replications completed or were already complete: $root"
