param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/test_current_scale_timing_20260915",
    [int]$MaxParallel = 3,
    [int]$SolverThreads = 1,
    [int]$LimitSeconds = 1800
)

$ErrorActionPreference = "Stop"
if ($MaxParallel -lt 1 -or $MaxParallel -gt 3 -or $SolverThreads -lt 1 -or $LimitSeconds -lt 1) {
    throw "Use 1--3 parallel processes and positive solver settings."
}
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$root = [System.IO.Path]::GetFullPath((Join-Path $project $OutputRoot))
$instance = Join-Path $project "analysis\TRB_reviewer_revision\100_svu_experiment12_baseline_cases_20260915\rep_000\instance\instance.tsv"
$classes = Join-Path $root "classes"
$logs = Join-Path $root "logs"
$results = Join-Path $root "results"
New-Item -ItemType Directory -Force -Path $classes,$logs,$results | Out-Null
$libraries = "D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar"
& javac -encoding UTF-8 -Xprefer:source -cp "$classes;$(Join-Path $project 'bin');$libraries" `
    -sourcepath (Join-Path $project "src") -d $classes `
    (Join-Path $project "src\Test\analysis\synthetic\TRBSVUCurrentScaleTimingMain.java")
if ($LASTEXITCODE -ne 0) { throw "Timing runner compilation failed." }
$classpath = "$classes;$(Join-Path $project 'bin');$libraries"
$native = "D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64"
$methods = @("D","SAA","TUNED_SAA","CSAA_EXP","RF_CSAA","U_CHI2","C_CHI2",
    "RSAA","RCSAA","U_W1","C_W1","U_PCM","C_PCM")
$pending = [System.Collections.Generic.Queue[string]]::new()
foreach ($method in $methods) { $pending.Enqueue($method) }
$running = [System.Collections.Generic.List[object]]::new()
$status = [System.Collections.Generic.List[object]]::new()
while ($pending.Count -gt 0 -or $running.Count -gt 0) {
    while ($pending.Count -gt 0 -and $running.Count -lt $MaxParallel) {
        $method = $pending.Dequeue()
        $out = Join-Path $results "$method.tsv"
        $stdout = Join-Path $logs "$method.out.log"
        $stderr = Join-Path $logs "$method.err.log"
        $arguments = @("-Djava.library.path=$native", "-cp", $classpath,
            "Test.analysis.synthetic.TRBSVUCurrentScaleTimingMain",
            $instance, $method, $out, $SolverThreads, $LimitSeconds)
        $process = Start-Process -FilePath java -ArgumentList $arguments -WorkingDirectory $project `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
        $running.Add([pscustomobject]@{Method=$method;Process=$process;Started=Get-Date;Result=$out})
    }
    for ($i=$running.Count-1; $i-ge 0; $i--) {
        $job=$running[$i]
        if (-not $job.Process.HasExited) { continue }
        $job.Process.WaitForExit()
        $status.Add([pscustomobject]@{method=$job.Method;exit_code=$job.Process.ExitCode;
            elapsed_seconds=[math]::Round(((Get-Date)-$job.Started).TotalSeconds,3);result=$job.Result})
        $running.RemoveAt($i)
        $status | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $root "status.csv")
    }
    if ($running.Count -gt 0) { Start-Sleep -Seconds 2 }
}
Write-Output "Timing probe complete: $root"
