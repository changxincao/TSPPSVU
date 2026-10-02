param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$PreviousStage)
$ErrorActionPreference='Stop'
$Root=(Resolve-Path -LiteralPath $Root).Path
$PreviousStage=(Resolve-Path -LiteralPath $PreviousStage).Path
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control|Out-Null
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
function Quote($s){"'"+$s.Replace("'","''")+"'"}
$log=Join-Path $control "pipeline_$(Get-Date -Format yyyyMMdd_HHmmss_fff).log"
$body='$ErrorActionPreference=''Stop'';try{& '+(Quote (Join-Path $PSScriptRoot 'run_olist_legacy_min_pipeline.ps1'))+
    ' -Root '+(Quote $Root)+' -PreviousStage '+(Quote $PreviousStage)+' *>&1|Tee-Object -FilePath '+(Quote $log)+
    '}catch{$_|Out-String|Add-Content -LiteralPath '+(Quote $log)+';exit 1}'
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
$startedPid=[OlistWindowsProcess]::StartDetached('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
    "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded",$Root)
[pscustomobject]@{pid=$startedPid;started=(Get-Date -Format o);root=$Root;previousStage=$PreviousStage;log=$log}|
    ConvertTo-Json|Set-Content -LiteralPath (Join-Path $control 'pipeline_launcher.json') -Encoding UTF8
Write-Host "Detached legacy min pipeline PID=$startedPid; existing workers unchanged"
