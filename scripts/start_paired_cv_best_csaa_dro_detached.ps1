param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot,
    [ValidateRange(1, 4)][int]$MaxParallel = 4
)
$ErrorActionPreference = 'Stop'
$TaskRoot = (Resolve-Path -LiteralPath $TaskRoot).Path
$ExperimentRoot = (Resolve-Path -LiteralPath $ExperimentRoot).Path
$control = Join-Path $ExperimentRoot 'control\dro20_after_baselines'
New-Item -ItemType Directory -Force -Path $control | Out-Null
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
$launcher = Join-Path $control 'launcher.json'
if (Test-Path -LiteralPath $launcher) {
    $old = Get-Content -LiteralPath $launcher -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id ([int]$old.pid) -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$old.startUtcTicks) {
        Write-Output "Already queued: detached DRO scheduler PID=$($old.pid)"
        return
    }
}
function Quote([string]$value) { "'" + $value.Replace("'", "''") + "'" }
$script = Join-Path $PSScriptRoot 'run_paired_cv_best_csaa_dro_after_baselines.ps1'
$log = Join-Path $control ('scheduler_{0}.log' -f (Get-Date -Format yyyyMMdd_HHmmss_fff))
$body = '$ErrorActionPreference=''Stop''; try { & ' + (Quote $script) +
    ' -TaskRoot ' + (Quote $TaskRoot) + ' -ExperimentRoot ' + (Quote $ExperimentRoot) +
    ' -MaxParallel ' + $MaxParallel + ' *>&1 | Tee-Object -FilePath ' + (Quote $log) +
    ' } catch { $_ | Out-String | Add-Content -LiteralPath ' + (Quote $log) + '; exit 1 }'
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
$startedPid = [OlistWindowsProcess]::StartDetached(
    'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
    "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded", $TaskRoot)
$process = Get-Process -Id $startedPid -ErrorAction Stop
[pscustomobject]@{
    pid = $startedPid; startUtcTicks = $process.StartTime.ToUniversalTime().Ticks
    started = [DateTime]::Now.ToString('o'); taskRoot = $TaskRoot; root = $ExperimentRoot
    log = $log; parallel = $MaxParallel; solverThreads = 4
    plan = 'Low RF+Tri; other CV cells RF; lambda 0.1,0.25,0.5,1'
} | ConvertTo-Json | Set-Content -LiteralPath $launcher -Encoding UTF8
Write-Output "Detached DRO queue PID=$startedPid; existing baseline solvers unchanged."
