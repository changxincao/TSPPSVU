param([Parameter(Mandatory=$true)][string]$BaseRoot, [Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$BaseRoot=(Resolve-Path -LiteralPath $BaseRoot).Path
$Root=(Resolve-Path -LiteralPath $Root).Path
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
function Quote($s) { "'"+$s.Replace("'","''")+"'" }
$log=Join-Path $control "scheduler_$(Get-Date -Format yyyyMMdd_HHmmss_fff).log"
$body='$ErrorActionPreference=''Stop''; try { & '+(Quote (Join-Path $PSScriptRoot 'run_olist_best_csaa_dro.ps1'))+
    ' -BaseRoot '+(Quote $BaseRoot)+' -Root '+(Quote $Root)+' *>&1 | Tee-Object -FilePath '+(Quote $log)+
    ' } catch { $_ | Out-String | Add-Content -LiteralPath '+(Quote $log)+'; exit 1 }'
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
$startedPid=[OlistWindowsProcess]::StartDetached('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
    "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded",$Root)
[pscustomobject]@{pid=$startedPid;started=(Get-Date -Format o);root=$Root;baseRoot=$BaseRoot;log=$log}|
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'launcher.json') -Encoding UTF8
Write-Host "Detached Olist DRO waiter PID=$startedPid; base solvers unchanged"
