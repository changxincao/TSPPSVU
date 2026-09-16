$ErrorActionPreference = 'Stop'
Set-Location 'D:\软件\eclipse\workspace\TransportationProcurement'

$root = 'analysis\巴西数据分析\新版_purchase时间\输出\04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果\01_原始滚动CV选参输出'
$chunkRoot = Join-Path $root 'chunks'
$stateFile = Join-Path $root 'run_state.txt'
$summaryFile = Join-Path $root 'chunk_status.csv'
$stdoutLog = Join-Path $root 'supervisor_stdout.log'
$stderrLog = Join-Path $root 'supervisor_stderr.log'
$javaExe = 'D:\软件\Java\jdk_22\bin\java.exe'
$javaCp = 'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar'
$dailyCsv = 'analysis\巴西数据分析\新版_purchase时间\输入\聚合需求表_日度与周度\按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv'

New-Item -ItemType Directory -Force -Path $root | Out-Null
New-Item -ItemType Directory -Force -Path $chunkRoot | Out-Null

'[{0}] supervisor_start pid={1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $PID | Set-Content -Path $stdoutLog -Encoding UTF8
'' | Set-Content -Path $stderrLog -Encoding UTF8
'phase,timestamp,chunk,startTrial,trialCount,pid,exitCode,outDir' | Set-Content -Path $summaryFile -Encoding UTF8

$chunks = @(
  [pscustomobject]@{ Name = 'chunk_00_12'; StartTrial = 0;  TrialCount = 13 },
  [pscustomobject]@{ Name = 'chunk_13_25'; StartTrial = 13; TrialCount = 13 },
  [pscustomobject]@{ Name = 'chunk_26_38'; StartTrial = 26; TrialCount = 13 },
  [pscustomobject]@{ Name = 'chunk_39_50'; StartTrial = 39; TrialCount = 12 }
)

$running = @()
foreach ($chunk in $chunks) {
  $outDir = Join-Path $chunkRoot $chunk.Name
  New-Item -ItemType Directory -Force -Path $outDir | Out-Null
  $chunkStdOut = Join-Path $outDir 'run_stdout.log'
  $chunkStdErr = Join-Path $outDir 'run_stderr.log'
  $args = @(
    '-Djava.library.path=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
    '-cp',
    $javaCp,
    'Test.BrazilOlistAdaptiveCVSolveComparison',
    $dailyCsv,
    '10',
    '50',
    $outDir,
    [string]$chunk.StartTrial,
    [string]$chunk.TrialCount,
    '1'
  )
  $proc = Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $chunkStdOut -RedirectStandardError $chunkStdErr -PassThru
  $running += [pscustomobject]@{
    Name = $chunk.Name
    StartTrial = $chunk.StartTrial
    TrialCount = $chunk.TrialCount
    OutDir = $outDir
    Proc = $proc
  }
  ('start,{0},{1},{2},{3},{4},,{5}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $chunk.Name, $chunk.StartTrial, $chunk.TrialCount, $proc.Id, $outDir) |
    Add-Content -Path $summaryFile -Encoding UTF8
}

function Merge-Csv([string]$targetPath, [string[]]$sourcePaths) {
  $headerWritten = $false
  if (Test-Path $targetPath) {
    Remove-Item -Force $targetPath
  }
  foreach ($path in $sourcePaths) {
    if (!(Test-Path $path)) { continue }
    $lines = Get-Content -Path $path
    if ($lines.Count -eq 0) { continue }
    if (-not $headerWritten) {
      $lines | Set-Content -Path $targetPath -Encoding UTF8
      $headerWritten = $true
    } elseif ($lines.Count -gt 1) {
      $lines | Select-Object -Skip 1 | Add-Content -Path $targetPath -Encoding UTF8
    }
  }
}

$allDone = $false
while (-not $allDone) {
  $allDone = $true
  $runningCount = 0
  foreach ($job in $running) {
    $job.Proc.Refresh()
    if (-not $job.Proc.HasExited) {
      $allDone = $false
      $runningCount++
    }
  }
  '[{0}] running chunks={1}/{2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $runningCount, $running.Count | Set-Content -Path $stateFile -Encoding UTF8
  if (-not $allDone) {
    Start-Sleep -Seconds 15
  }
}

$failed = @()
foreach ($job in $running) {
  $job.Proc.Refresh()
  ('finish,{0},{1},{2},{3},{4},{5},{6}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $job.Name, $job.StartTrial, $job.TrialCount, $job.Proc.Id, $job.Proc.ExitCode, $job.OutDir) |
    Add-Content -Path $summaryFile -Encoding UTF8
  if ($job.Proc.ExitCode -ne 0) {
    $failed += $job
  }
}

if ($failed.Count -gt 0) {
  '[{0}] failed chunks={1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), ($failed.Name -join ';') | Set-Content -Path $stateFile -Encoding UTF8
  exit 1
}

$selectionSources = $chunks | ForEach-Object { Join-Path (Join-Path $chunkRoot $_.Name) 'cv_selected_params.csv' }
$stage1Sources = $chunks | ForEach-Object { Join-Path (Join-Path $chunkRoot $_.Name) 'cv_stage1_k_c_candidates.csv' }
$stage2Sources = $chunks | ForEach-Object { Join-Path (Join-Path $chunkRoot $_.Name) 'cv_stage2_lambda_candidates.csv' }
$selectedActualSources = $chunks | ForEach-Object { Join-Path (Join-Path $chunkRoot $_.Name) 'cv_selected_actual_trials.csv' }

Merge-Csv -targetPath (Join-Path $root 'cv_selected_params.csv') -sourcePaths $selectionSources
Merge-Csv -targetPath (Join-Path $root 'cv_stage1_k_c_candidates.csv') -sourcePaths $stage1Sources
Merge-Csv -targetPath (Join-Path $root 'cv_stage2_lambda_candidates.csv') -sourcePaths $stage2Sources
Merge-Csv -targetPath (Join-Path $root 'cv_selected_actual_trials.csv') -sourcePaths $selectedActualSources

'[{0}] all_done merged_outputs_ready' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Set-Content -Path $stateFile -Encoding UTF8
Add-Content -Path $stdoutLog -Value ('[{0}] merged_outputs_ready' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8
