$ErrorActionPreference = 'Stop'

$workspace = 'D:\软件\eclipse\workspace\TransportationProcurement'
Set-Location $workspace

$env:PATH = 'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\软件\Mosek\11.0\tools\platform\win64x86\bin;' + $env:PATH
$javaExe = 'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe'
$javaCp = 'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'

$dailyCsv = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输入\聚合需求表_日度与周度\按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv'
$testRoot = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输出\08_额外测试_极小权重替代与大lambda扩展\02_保持当前代码_扩展lambda到300_500_1000'
$rawRoot = Join-Path $testRoot '01_原始求解目录'
$logRoot = Join-Path $testRoot 'runlog'
$stateFile = Join-Path $logRoot 'state.txt'

New-Item -ItemType Directory -Force -Path $rawRoot | Out-Null
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

function Append-State([string]$msg) {
  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  $line | Out-File -FilePath $stateFile -Encoding utf8 -Append
}

function Has-GlobalSummary([string]$dir) {
  Test-Path (Join-Path $dir 'global_summary.csv')
}

function Run-Jobs([object[]]$jobs) {
  $running = @()
  $idx = 0
  $maxParallel = 4
  while ($idx -lt $jobs.Count -or $running.Count -gt 0) {
    while ($idx -lt $jobs.Count -and $running.Count -lt $maxParallel) {
      $job = $jobs[$idx]
      $proc = Start-Process -FilePath $javaExe -ArgumentList $job.Args -WorkingDirectory $workspace -RedirectStandardOutput $job.StdOut -RedirectStandardError $job.StdErr -PassThru
      $running += [pscustomobject]@{ Name = $job.Name; Proc = $proc }
      Append-State ('START {0} pid={1}' -f $job.Name, $proc.Id)
      $idx++
    }
    Start-Sleep -Seconds 10
    $still = @()
    foreach ($r in $running) {
      if ($r.Proc.HasExited) {
        Append-State ('DONE {0} exit={1}' -f $r.Name, $r.Proc.ExitCode)
      } else {
        $still += $r
      }
    }
    $running = $still
  }
}

$chValues = @('0.10', '0.50', '1.00', '100.00')
$lambdas = @('300.00', '500.00', '1000.00')
$jobs = @()

Append-State 'RUN_START'
Append-State ('dailyCsv={0}' -f $dailyCsv)
Append-State ('rawRoot={0}' -f $rawRoot)

foreach ($c in $chValues) {
  foreach ($lambda in $lambdas) {
    $outDir = Join-Path $rawRoot ('RCSAA_W50_k1=1_raw_C{0}_lambda{1}' -f $c, $lambda)
    if (Has-GlobalSummary $outDir) {
      Append-State ('SKIP existing C={0} lambda={1}' -f $c, $lambda)
      continue
    }
    $jobs += [pscustomobject]@{
      Name = ('test2_k1=1_C={0}_lambda={1}' -f $c, $lambda)
      Args = @(
        '-cp', $javaCp,
        'Test.analysis.brazil.BrazilOlistThetaModeSolveRunner',
        $dailyCsv,
        '10',
        '50',
        '1',
        $outDir,
        $c,
        'rcsaa',
        'raw',
        $lambda
      )
      StdOut = (Join-Path $logRoot ('log_test2_k1=1_C{0}_L{1}_out.txt' -f $c, $lambda))
      StdErr = (Join-Path $logRoot ('log_test2_k1=1_C{0}_L{1}_err.txt' -f $c, $lambda))
    }
  }
}

Append-State ('JOB_COUNT {0}' -f $jobs.Count)
if ($jobs.Count -gt 0) {
  Run-Jobs $jobs
  Append-State 'ALL_DONE'
} else {
  Append-State 'NOTHING_TO_RUN'
}
