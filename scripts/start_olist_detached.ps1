param([string]$Root = (Split-Path $PSScriptRoot -Parent), [string]$PreviousStage)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'control') | Out-Null
$script = Join-Path $Root 'scripts/run_olist_batch.ps1'
$waitArguments = ''
if ($PreviousStage) {
    $PreviousStage = (Resolve-Path -LiteralPath $PreviousStage).Path
    $script = Join-Path $Root 'scripts/run_olist_after_stage.ps1'
    $waitArguments = ' -PreviousStage ' + "'" + $PreviousStage.Replace("'", "''") + "'"
}
$stamp = Get-Date -Format yyyyMMdd_HHmmss_fff
$log = Join-Path $Root "control/scheduler_$stamp.log"
function Quote-Literal($Value) { "'" + $Value.Replace("'", "''") + "'" }
$body = '$ErrorActionPreference = ''Stop''; try { & ' + (Quote-Literal $script) + ' -Root ' + (Quote-Literal $Root) + $waitArguments +
    ' *>&1 | Tee-Object -FilePath ' + (Quote-Literal $log) +
    ' } catch { $_ | Out-String | Add-Content -LiteralPath ' + (Quote-Literal $log) + '; exit 1 }'
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
$startedPid = [OlistWindowsProcess]::StartDetached(
    'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
    "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded", $Root)
[pscustomobject]@{pid=$startedPid;started=(Get-Date -Format o);root=$Root;previousStage=$PreviousStage;log=$log} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'control/launcher.json') -Encoding UTF8
Write-Host "Detached scheduler PID=$startedPid; inspect control/status.json, control/wait_status.json and $log."
