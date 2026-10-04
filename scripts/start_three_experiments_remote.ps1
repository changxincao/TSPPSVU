param([Parameter(Mandatory=$true)][string]$Manifest)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$plan=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json
$script=Join-Path $plan.deployment 'scripts\run_three_experiments_remote.ps1'
$control=Split-Path $Manifest -Parent
. (Join-Path $plan.deployment 'scripts\olist_windows_process.ps1')
$tokens=$null;$parseErrors=$null
[System.Management.Automation.Language.Parser]::ParseFile($script,[ref]$tokens,[ref]$parseErrors)|Out-Null
if($parseErrors.Count){throw ($parseErrors|Out-String)}
$controllers=@(Get-Process powershell -ErrorAction SilentlyContinue|Where-Object{
    try{$cmd=[OlistWindowsProcess]::CommandLine($_.Id);$cmd.Contains($script)-and$cmd.Contains('-Manifest')-and$cmd.Contains($Manifest)}catch{$false}
})
if($controllers.Count){throw 'Existing controller; refusing duplicate launch'}
& $script -Manifest $Manifest -CheckOnly *> "$control\queue_check.log"
$pidStarted=[OlistWindowsProcess]::StartDetached('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
    "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -Manifest `"$Manifest`"",$plan.deployment)
[pscustomobject]@{pid=$pidStarted;host=$env:COMPUTERNAME;started=(Get-Date -Format o);detached=$true;
    parallel=4;solverThreads=4;manifest=$Manifest;command=[OlistWindowsProcess]::CommandLine($pidStarted)}|
    ConvertTo-Json|Set-Content -LiteralPath "$control\controller_start.json" -Encoding UTF8
Get-Content -LiteralPath "$control\controller_start.json"
