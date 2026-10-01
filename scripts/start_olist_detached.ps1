param([string]$Root = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'control') | Out-Null
$script = Join-Path $Root 'scripts/run_olist_batch.ps1'
$stamp = Get-Date -Format yyyyMMdd_HHmmss_fff
$log = Join-Path $Root "control/scheduler_$stamp.log"
function Quote-Literal($Value) { "'" + $Value.Replace("'", "''") + "'" }
$body = '$ErrorActionPreference = ''Stop''; try { & ' + (Quote-Literal $script) + ' -Root ' + (Quote-Literal $Root) +
    ' *>&1 | Tee-Object -FilePath ' + (Quote-Literal $log) +
    ' } catch { $_ | Out-String | Add-Content -LiteralPath ' + (Quote-Literal $log) + '; exit 1 }'
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ShowWindow=[uint16]0}
# Created by the local WMI service, not a child of the SSH shell/job.
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine="powershell.exe -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded";
    CurrentDirectory=$Root; ProcessStartupInformation=$startup
}
if ($created.ReturnValue -ne 0) { throw "WMI detached launch failed: code=$($created.ReturnValue). Nothing was declared started." }
Write-Host "Detached scheduler PID=$($created.ProcessId); inspect control/status.json and $log."
