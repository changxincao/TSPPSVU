$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repoRoot

$tailRoot = Join-Path $repoRoot 'analysis\purchase_raw_cv_10sup_full_4proc_live_tail'
$liveChunksRoot = Join-Path $repoRoot 'analysis\purchase_raw_cv_10sup_full_4proc_live\chunks'

$latestRunPtr = Join-Path $tailRoot 'latest_run_root.txt'
$tailRunRoot = $null
if (Test-Path $latestRunPtr) {
  $line = (Get-Content -Path $latestRunPtr -ErrorAction SilentlyContinue | Select-Object -First 1)
  if ($line -and $line.StartsWith('runRoot=')) {
    $tailRunRoot = $line.Substring('runRoot='.Length).Trim()
  }
}
if (-not $tailRunRoot) {
  $tailRunRoot = (Join-Path $tailRoot 'rerun_latest')
}

$watchTrials = @(10,11,12,24,25)
$pollSeconds = 30
$stateFile = Join-Path $tailRoot 'monitor_state.txt'

function TrialDirName([int]$t) { return ('trial_{0:00}' -f $t) }

function TrialDone([string]$dir) {
  $sel = Join-Path $dir 'cv_selected_params.csv'
  if (!(Test-Path $sel)) { return $false }
  $lines = (Get-Content -Path $sel).Count
  return $lines -ge 2
}

function TrialError([string]$dir) {
  $err = Join-Path $dir 'run_stderr.log'
  if (!(Test-Path $err)) { return $false }
  return (Get-Item $err).Length -gt 0
}

while ($true) {
  $done = @()
  $pending = @()
  $errored = @()

  foreach ($t in $watchTrials) {
    $dir = Join-Path $tailRunRoot (TrialDirName $t)
    if (TrialError $dir) { $errored += $t; continue }
    if (TrialDone $dir) { $done += $t } else { $pending += $t }
  }

  $msg = '[{0}] tailRun={1} done={2} pending={3} errored={4}' -f `
    (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $tailRunRoot, `
    ($done -join ','), ($pending -join ','), ($errored -join ',')
  $msg | Set-Content -Encoding UTF8 $stateFile

  if ($errored.Count -gt 0) {
    throw ("Some trials errored: " + ($errored -join ',') + ". Check their run_stderr.log under: " + $tailRunRoot)
  }

  if ($pending.Count -eq 0) { break }
  Start-Sleep -Seconds $pollSeconds
}

foreach ($t in $watchTrials) {
  $src = Join-Path $tailRunRoot (TrialDirName $t)
  $dst = Join-Path $liveChunksRoot ('tail_' + (TrialDirName $t))
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  Copy-Item -Force (Join-Path $src 'cv_selected_params.csv') (Join-Path $dst 'cv_selected_params.csv')
  Copy-Item -Force (Join-Path $src 'cv_stage1_k_c_candidates.csv') (Join-Path $dst 'cv_stage1_k_c_candidates.csv')
  Copy-Item -Force (Join-Path $src 'cv_stage2_lambda_candidates.csv') (Join-Path $dst 'cv_stage2_lambda_candidates.csv')
}

$outRoot = Join-Path $repoRoot ('analysis\purchase_raw_cv_perk_bestC_then_lambda_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

$javaExe = 'D:\软件\Java\jdk_22\bin\java.exe'
$javaCp = 'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'
$dailyCsv = 'analysis\巴西数据分析\旧版\五大区合并后日度OD需求表_千克.csv'

$stdout = Join-Path $outRoot 'run_stdout.log'
$stderr = Join-Path $outRoot 'run_stderr.log'
$args = @(
  '-Djava.library.path=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
  '-cp',
  $javaCp,
  'Test.BrazilOlistPerKBestCThenLambdaFromExistingOutputs',
  $dailyCsv,
  '10',
  '50',
  $liveChunksRoot,
  $outRoot
)

$proc = Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $repoRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
('[{0}] started perk runner pid={1} outRoot={2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $proc.Id, $outRoot) | Add-Content -Encoding UTF8 $stateFile
