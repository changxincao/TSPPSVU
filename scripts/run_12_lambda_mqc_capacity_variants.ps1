param(
  [int]$WorkerIndex = 0,
  [int]$WorkerCount = 1
)

$ErrorActionPreference = 'Stop'

$workspace = 'D:\软件\eclipse\workspace\TransportationProcurement'
Set-Location $workspace

$env:PATH = 'D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\软件\Mosek\11.0\tools\platform\win64x86\bin;' + $env:PATH
$javaExe = 'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe'
$javaCp = 'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'

$dailyCsv = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输入\聚合需求表_日度与周度\按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv'
$root = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输出\12_lambda实验_MQC与capacity两组新配置'
$rootLog = Join-Path $root 'runlog'
$rootState = Join-Path $rootLog 'state.txt'

New-Item -ItemType Directory -Force -Path $root | Out-Null
New-Item -ItemType Directory -Force -Path $rootLog | Out-Null

function Append-State([string]$path, [string]$msg) {
  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  $line | Out-File -FilePath $path -Encoding utf8 -Append
}

function Has-GlobalSummary([string]$dir) {
  Test-Path (Join-Path $dir 'global_summary.csv')
}

function New-VariantRoot([string]$name) {
  $variantRoot = Join-Path $root $name
  foreach ($sub in @('01_raw_outputs', '02_processed', '03_figures', 'runlog')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $variantRoot $sub) | Out-Null
  }
  return $variantRoot
}

$variants = @(
  [pscustomobject]@{
    Name = '01_MQC_0.3-0.4_capacity_0.5-0.7'
    MqcLow = '0.3'
    MqcHigh = '0.4'
    CapLow = '0.5'
    CapHigh = '0.7'
  },
  [pscustomobject]@{
    Name = '02_MQC_0.5-0.6_capacity_0.8-1.0'
    MqcLow = '0.5'
    MqcHigh = '0.6'
    CapLow = '0.8'
    CapHigh = '1.0'
  }
)

$chValues = @('0.10', '0.50', '1.00', '100.00')
$lambdas = @('0.01', '0.05', '0.10', '1.00', '5.00', '10.00', '50.00', '100.00', '300.00', '500.00', '1000.00', '10000.00', '50000.00', '100000.00')

$jobs = @()
foreach ($variant in $variants) {
  $variantRoot = New-VariantRoot $variant.Name
  $rawRoot = Join-Path $variantRoot '01_raw_outputs'
  $logRoot = Join-Path $variantRoot 'runlog'
  $stateFile = Join-Path $logRoot 'state.txt'

  Append-State $stateFile ('WORKER_START worker={0}/{1}' -f $WorkerIndex, $WorkerCount)
  Append-State $stateFile ('dailyCsv={0}' -f $dailyCsv)
  Append-State $stateFile ('mqc=[{0},{1}], capacity=[{2},{3}]' -f $variant.MqcLow, $variant.MqcHigh, $variant.CapLow, $variant.CapHigh)

  foreach ($c in $chValues) {
    $csaaOutDir = Join-Path $rawRoot ('CSAA_W50_k1=1_raw_C{0}' -f $c)
    $jobs += [pscustomobject]@{
      Name = ('{0}_CSAA_C={1}' -f $variant.Name, $c)
      StateFile = $stateFile
      OutDir = $csaaOutDir
      Args = @(
        '-cp', $javaCp,
        'Test.analysis.brazil.BrazilOlistThetaModeSolveRunner',
        $dailyCsv,
        '10',
        '50',
        '1',
        $csaaOutDir,
        $c,
        'csaa',
        'raw',
        '0',
        $variant.MqcLow,
        $variant.MqcHigh,
        $variant.CapLow,
        $variant.CapHigh
      )
      StdOut = (Join-Path $logRoot ('log_CSAA_C{0}_out.txt' -f $c))
      StdErr = (Join-Path $logRoot ('log_CSAA_C{0}_err.txt' -f $c))
    }

    foreach ($lambda in $lambdas) {
      $outDir = Join-Path $rawRoot ('RCSAA_W50_k1=1_raw_C{0}_lambda{1}' -f $c, $lambda)
      $jobs += [pscustomobject]@{
        Name = ('{0}_RCSAA_C={1}_lambda={2}' -f $variant.Name, $c, $lambda)
        StateFile = $stateFile
        OutDir = $outDir
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
          $lambda,
          $variant.MqcLow,
          $variant.MqcHigh,
          $variant.CapLow,
          $variant.CapHigh
        )
        StdOut = (Join-Path $logRoot ('log_RCSAA_C{0}_L{1}_out.txt' -f $c, $lambda))
        StdErr = (Join-Path $logRoot ('log_RCSAA_C{0}_L{1}_err.txt' -f $c, $lambda))
      }
    }
  }
}

Append-State $rootState ('WORKER_START worker={0}/{1}' -f $WorkerIndex, $WorkerCount)
Append-State $rootState ('dailyCsv={0}' -f $dailyCsv)
Append-State $rootState ('JOB_COUNT_TOTAL {0}' -f $jobs.Count)

for ($i = 0; $i -lt $jobs.Count; $i++) {
  if (($i % $WorkerCount) -ne $WorkerIndex) { continue }
  $job = $jobs[$i]
  if (Has-GlobalSummary $job.OutDir) {
    Append-State $rootState ('SKIP worker={0} {1} already_done' -f $WorkerIndex, $job.Name)
    Append-State $job.StateFile ('SKIP worker={0} {1} already_done' -f $WorkerIndex, $job.Name)
    continue
  }
  Append-State $rootState ('START worker={0} {1}' -f $WorkerIndex, $job.Name)
  Append-State $job.StateFile ('START worker={0} {1}' -f $WorkerIndex, $job.Name)
  try {
    & $javaExe @($job.Args) 1> $job.StdOut 2> $job.StdErr
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) { $exitCode = 0 }
    Append-State $rootState ('DONE worker={0} {1} exit={2}' -f $WorkerIndex, $job.Name, $exitCode)
    Append-State $job.StateFile ('DONE worker={0} {1} exit={2}' -f $WorkerIndex, $job.Name, $exitCode)
    if ($exitCode -ne 0) {
      throw ('java exit code {0}' -f $exitCode)
    }
  } catch {
    Append-State $rootState ('FAIL worker={0} {1} {2}' -f $WorkerIndex, $job.Name, $_.Exception.Message)
    Append-State $job.StateFile ('FAIL worker={0} {1} {2}' -f $WorkerIndex, $job.Name, $_.Exception.Message)
    throw
  }
}

Append-State $rootState ('WORKER_DONE worker={0}' -f $WorkerIndex)
