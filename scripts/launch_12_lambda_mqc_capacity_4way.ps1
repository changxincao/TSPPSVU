$ErrorActionPreference = 'Stop'

$workspace = 'D:\软件\eclipse\workspace\TransportationProcurement'
Set-Location $workspace

$root = 'D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输出\12_lambda实验_MQC与capacity两组新配置'
$rootLog = Join-Path $root 'runlog'
New-Item -ItemType Directory -Force -Path $rootLog | Out-Null
$stateFile = Join-Path $rootLog 'launch_state.txt'

function Append-State([string]$msg) {
  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  $line | Out-File -FilePath $stateFile -Encoding utf8 -Append
}

$workerScript = 'D:\软件\eclipse\workspace\TransportationProcurement\scripts\run_12_lambda_mqc_capacity_variants.ps1'
Append-State 'LAUNCH_START'
for ($w = 0; $w -lt 4; $w++) {
  $proc = Start-Process -FilePath 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $workerScript, '-WorkerIndex', $w, '-WorkerCount', 4) -PassThru
  Append-State ('START worker={0} pid={1}' -f $w, $proc.Id)
}
Append-State 'LAUNCH_DONE'
