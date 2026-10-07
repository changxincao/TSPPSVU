param([switch]$Run)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$taskRoot = Join-Path $workspace 'analysis_runs\w1_linf_monotone_probe_20261007'
$queueRoot = Join-Path $taskRoot 'cv_large'
$launcher = Join-Path $PSScriptRoot 'start_local_w1_linf_cv.ps1'
if (!$Run) {
    $existing = @(Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -like '*queue_local_w1_linf_extended.ps1*' -and $_.CommandLine -match '-Run(?:\s|$)'
    })
    if ($existing.Count) { throw 'Extended queue controller already active' }
    & $launcher -Extended -CheckOnly
    $shell = Join-Path $PSHOME 'powershell.exe'
    if (!(Test-Path -LiteralPath $shell)) { $shell = Join-Path $PSHOME 'pwsh.exe' }
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"",'-Run')
    $proc = Start-Process -FilePath $shell -ArgumentList $arguments -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$queueRoot\queue.$stamp.stdout.log" -RedirectStandardError "$queueRoot\queue.$stamp.stderr.log"
    [pscustomobject]@{pid=$proc.Id;queued=(Get-Date -Format o);waitFor='existing local Linf workers';parallel=2;threads=4} |
        Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath "$queueRoot\queue_controller.csv"
    Write-Output "EXTENDED_QUEUE_STARTED pid=$($proc.Id); waits for current two workers; no interruption"
    return
}
try {
    'WAITING_FOR_SMALL_GRID_WORKERS' | Set-Content -Encoding UTF8 -LiteralPath "$queueRoot\queue_status.txt"
    while (@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -like '*w1_linf_monotone_probe_20261007*' -and
        ($_.CommandLine -like '*TRBSVUWassersteinInfinityCvMain*' -or $_.CommandLine -like '*TRBSVUWassersteinInfinityProbe*')
    }).Count -gt 0) { Start-Sleep -Seconds 20 }
    'STARTING_EXTENDED_ROLLING_CV' | Set-Content -Encoding UTF8 -LiteralPath "$queueRoot\queue_status.txt"
    & $launcher -Extended
    'EXTENDED_WORKERS_LAUNCHED' | Set-Content -Encoding UTF8 -LiteralPath "$queueRoot\queue_status.txt"
} catch {
    $_ | Out-String | Set-Content -Encoding UTF8 -LiteralPath "$queueRoot\queue_failure.txt"
    throw
}
