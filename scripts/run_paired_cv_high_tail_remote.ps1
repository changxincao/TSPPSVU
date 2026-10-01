param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$ExperimentRoot,
      [int]$ExpectedParentPid=0,
      [ValidateRange(1,4)][int]$MaxParallel=4)
$ErrorActionPreference='Stop'
$parentRoot=Join-Path (Split-Path -Parent $ExperimentRoot) 'paired_cv_best_csaa_20260930'
$parentStatus=Join-Path $parentRoot 'control\status.json'
$control=Join-Path $ExperimentRoot 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$statusPath=Join-Path $control 'status.json'
function Write-Status($value) {
    $json=$value | ConvertTo-Json
    for($attempt=1;$attempt -le 40;$attempt++) {
        try {
            Set-Content -LiteralPath $statusPath -Value $json -Encoding UTF8 -ErrorAction Stop
            return
        } catch [System.IO.IOException] {
            if($attempt -eq 40) {throw}
            Start-Sleep -Milliseconds 250
        }
    }
}

try {
    foreach($rep in 0..4) {
        $manifest=Join-Path $ExperimentRoot ('cv050070\input\rep_{0:D3}\instance\manifest.txt' -f $rep)
        $lines=Get-Content -LiteralPath $manifest -Encoding UTF8
        if($lines -notcontains 'cvLower=0.5' -or $lines -notcontains 'cvUpper=0.7' -or
           $lines -notcontains "caseSeed=$([long]20261020+$rep)" -or $lines -notcontains 'queries=40') {
            throw "Invalid high-CV input: $manifest"
        }
    }
    $record=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $parentRoot 'run_paired_cv_csaa_remote_launcher_process.txt')) {
        $pair=$line.Split('=',2);if($pair.Length -eq 2){$record[$pair[0]]=$pair[1]}
    }
    if($ExpectedParentPid -eq 0) {$ExpectedParentPid=[int]$record.controllerPid}
    if([int]$record.controllerPid -ne $ExpectedParentPid){throw 'Parent controller changed; recheck before queuing.'}
    while($true) {
        # The parent's JSON may be temporarily empty during its ten-second update.
        $parent=$null
        try {$parent=Get-Content -LiteralPath $parentStatus -Raw -Encoding UTF8 | ConvertFrom-Json}
        catch { }
        if($parent -and $parent.state -in @('FINISHED','PARTIAL') -and
           $parent.running -eq 0 -and $parent.queued -eq 0){break}
        $parentProcess=Get-Process -Id $expectedParentPid -ErrorAction SilentlyContinue
        if(-not $parentProcess){throw 'Parent exited without a terminal queue status; high-CV tasks remain unstarted.'}
        Write-Status ([pscustomobject]@{state='WAITING_FOR_CURRENT_THREE_CV';updated=[DateTime]::Now.ToString('o');
            parentRoot=$parentRoot;parentState=$parent.state;parentQueued=$parent.queued;
            parentRunning=$parent.running;queued=25;running=0;parallel=$MaxParallel;solverThreads=4})
        Start-Sleep -Seconds 30
    }
    Write-Status ([pscustomobject]@{state='STARTING_HIGH_CV';updated=[DateTime]::Now.ToString('o');
        parentState=$parent.state;queued=25;running=0})
    & (Join-Path $TaskRoot 'scripts\run_paired_cv_csaa_remote.ps1') `
        -TaskRoot $TaskRoot -ExperimentRoot $ExperimentRoot -Cells @('cv050070') -SkipCompleted -MaxParallel $MaxParallel
} catch {
    Write-Status ([pscustomobject]@{state='FAILED';updated=[DateTime]::Now.ToString('o');
        error=$_.Exception.Message})
    throw
}
