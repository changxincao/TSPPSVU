$ErrorActionPreference = 'Stop'

$workspace = 'D:\软件\eclipse\workspace\TransportationProcurement'
Set-Location $workspace

$env:PATH = 'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\软件\Mosek\11.0\tools\platform\win64x86\bin;' + $env:PATH
$javaExe = 'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe'
$javaCp = 'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'

$dailyCsv = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输入\聚合需求表_日度与周度\按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv'
$testRoot = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输出\10_lambda超大值延伸分析_k=1_剩余5组'
$expRoot = Join-Path $testRoot '01_原始求解目录'
$logRoot = Join-Path $testRoot 'runlog'
$stateFile = Join-Path $logRoot 'state.txt'

New-Item -ItemType Directory -Force -Path $expRoot | Out-Null
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

function Append-State([string]$msg) {
  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  $line | Out-File -FilePath $stateFile -Encoding utf8 -Append
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

$jobs = @()
$specs = @(
  @{ C = '1.00'; Lambda = '50000.00' },
  @{ C = '1.00'; Lambda = '100000.00' },
  @{ C = '100.00'; Lambda = '10000.00' },
  @{ C = '100.00'; Lambda = '50000.00' },
  @{ C = '100.00'; Lambda = '100000.00' }
)

Append-State 'RUN_START_10_REMAINING'
Append-State ('dailyCsv={0}' -f $dailyCsv)
Append-State ('expRoot={0}' -f $expRoot)

foreach ($spec in $specs) {
  $c = $spec.C
  $lambda = $spec.Lambda
  $outDir = Join-Path $expRoot ('RCSAA_W50_k1=1_raw_C{0}_lambda{1}' -f $c, $lambda)
  $jobs += [pscustomobject]@{
    Name = ('tail_k1=1_C={0}_lambda={1}' -f $c, $lambda)
    Args = @(
      '-cp', $javaCp,
      'Test.analysis.BrazilOlistThetaModeSolveRunner',
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
    StdOut = (Join-Path $logRoot ('log_tail_k1=1_C{0}_L{1}_out.txt' -f $c, $lambda))
    StdErr = (Join-Path $logRoot ('log_tail_k1=1_C{0}_L{1}_err.txt' -f $c, $lambda))
  }
}

Append-State ('JOB_COUNT {0}' -f $jobs.Count)
Run-Jobs $jobs
Append-State 'ALL_DONE_10_REMAINING'
