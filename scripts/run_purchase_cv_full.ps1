$ErrorActionPreference = 'Stop'
Set-Location 'D:\软件\eclipse\workspace\TransportationProcurement'
$outDir = 'analysis\purchase_raw_cv_10sup_full'
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$outLog = Join-Path $outDir 'run_stdout.log'
$errLog = Join-Path $outDir 'run_stderr.log'
$stateFile = Join-Path $outDir 'run_state.txt'
$javaPidFile = Join-Path $outDir 'java_pid.txt'
$psPidFile = Join-Path $outDir 'supervisor_pid.txt'
$argList = @(
  '-Djava.library.path=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64',
  '-cp',
  'bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar',
  'Test.BrazilOlistAdaptiveCVSolveComparison',
  'analysis\巴西数据分析\旧版\五大区合并后日度OD需求表_千克.csv',
  '10',
  '50',
  'analysis\purchase_raw_cv_10sup_full'
)
'[{0}] supervisor_start pid={1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $PID | Set-Content -Path $outLog -Encoding UTF8
'' | Set-Content -Path $errLog -Encoding UTF8
$PID | Set-Content -Path $psPidFile -Encoding ASCII
$proc = Start-Process -FilePath 'D:\软件\Java\jdk_22\bin\java.exe' -ArgumentList $argList -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
$proc.Id | Set-Content -Path $javaPidFile -Encoding ASCII
while (-not $proc.HasExited) {
  '[{0}] running supervisorPid={1} javaPid={2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $PID, $proc.Id | Set-Content -Path $stateFile -Encoding UTF8
  Start-Sleep -Seconds 15
  $proc.Refresh()
}
'[{0}] finished exit_code={1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $proc.ExitCode | Set-Content -Path $stateFile -Encoding UTF8
Add-Content -Path $outLog -Value ('[{0}] exit_code={1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $proc.ExitCode) -Encoding UTF8
exit $proc.ExitCode
