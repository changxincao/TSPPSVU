$ErrorActionPreference = 'Stop'

$sourceScript = Join-Path $PSScriptRoot 'backfill_rcsaa_k1_c010_to_03.ps1'
$sourceText = Get-Content $sourceScript -Raw

function Extract-One([string]$pattern) {
  $m = [regex]::Match($sourceText, $pattern)
  if (-not $m.Success) {
    throw "Pattern not found: $pattern"
  }
  return $m.Groups[1].Value
}

$workspace = Extract-One '\$workspace = ''([^'']+)'''
$pathPrefix = Extract-One '\$env:PATH = ''([^'']+)'' \+ \$env:PATH'
$javaExe = Extract-One '\$javaExe = ''([^'']+)'''
$javaCp = Extract-One '\$javaCp = ''([^'']+)'''
$dailyCsv = Extract-One '\$dailyCsv = ''([^'']+)'''
$expRoot = Extract-One '\$expRoot = ''([^'']+)'''

Set-Location $workspace
$env:PATH = $pathPrefix + $env:PATH

$logRoot = Join-Path $expRoot 'runlog_backfill_k1_c05_remaining4'
$stateFile = Join-Path $logRoot 'state.txt'
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
$c = '0.50'
$lambdas = @('0.10', '1.00', '5.00', '10.00')

Append-State 'RUN_START'
Append-State ('dailyCsv={0}' -f $dailyCsv)
Append-State ('expRoot={0}' -f $expRoot)

foreach ($lambda in $lambdas) {
  $outDir = Join-Path $expRoot ('RCSAA_W50_k1=1_raw_C{0}_lambda{1}' -f $c, $lambda)
  if (Test-Path $outDir) {
    Remove-Item -Recurse -Force $outDir
    Append-State ('REMOVE old_dir lambda={0}' -f $lambda)
  }
  $jobs += [pscustomobject]@{
    Name = ('k1=1_C={0}_lambda={1}' -f $c, $lambda)
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
    StdOut = (Join-Path $logRoot ('log_rcsaa_k1=1_C{0}_L{1}_out.txt' -f $c, $lambda))
    StdErr = (Join-Path $logRoot ('log_rcsaa_k1=1_C{0}_L{1}_err.txt' -f $c, $lambda))
  }
}

Append-State ('JOB_COUNT {0}' -f $jobs.Count)
if ($jobs.Count -gt 0) {
  Run-Jobs $jobs
  Append-State 'ALL_DONE'
} else {
  Append-State 'NOTHING_TO_RUN'
}
