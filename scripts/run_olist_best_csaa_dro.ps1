param([Parameter(Mandatory=$true)][string]$BaseRoot,
      [Parameter(Mandatory=$true)][string]$Root, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$BaseRoot = (Resolve-Path -LiteralPath $BaseRoot).Path
$Root = (Resolve-Path -LiteralPath $Root).Path
$cfg = [IO.File]::ReadAllText((Join-Path $BaseRoot 'config.json')) | ConvertFrom-Json
$control = Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock = [IO.File]::Open((Join-Path $control 'scheduler.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
function Write-Atomic($Path, $Text) {
    for($attempt=0; ; $attempt++) {
        try {
            [IO.File]::WriteAllText("$Path.tmp", $Text, (New-Object Text.UTF8Encoding($false)))
            if ([IO.File]::Exists($Path)) { [IO.File]::Replace("$Path.tmp", $Path, [NullString]::Value) }
            else { [IO.File]::Move("$Path.tmp", $Path) }
            return
        } catch {
            if($attempt -ge 9 -or $_.Exception.GetBaseException() -isnot [IO.IOException]){throw}
            Start-Sleep -Milliseconds 100
        }
    }
}
function Read-LiveJson($Path) {
    for($attempt=0; ; $attempt++) {
        $reader=$null; $stream=$null
        try {
            $stream=[IO.File]::Open($Path,'Open','Read',([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
            return (ConvertFrom-Json -InputObject $reader.ReadToEnd())
        } catch {
            if($attempt -ge 49 -or $_.Exception.GetBaseException() -isnot [IO.IOException]){throw}
            Start-Sleep -Milliseconds 100
        } finally {
            if($null -ne $reader){$reader.Dispose()}
            elseif($null -ne $stream){$stream.Dispose()}
        }
    }
}
function Event($Message) {
    Add-Content -LiteralPath (Join-Path $control 'events.log') -Value ((Get-Date -Format o) + ' ' + $Message)
    Write-Host $Message
}
try {
    $lib = Split-Path $cfg.mosekJar -Parent
    $native = "$($cfg.cplexNative);$lib"
    $env:Path = "$($cfg.cplexNative);$lib;$(Split-Path $cfg.java -Parent);$env:Path"
    $env:OPENBLAS_NUM_THREADS = '1'; $env:MKL_NUM_THREADS = '1'; $env:OMP_NUM_THREADS = '4'
    $cp = (Join-Path $Root 'runtime/classes') + ';' + $cfg.cplexJar + ';' + $cfg.mosekJar
    $trend = if ($cfg.includeTrend -eq $true) { 'true' } else { 'false' }
    $common = @('-Xmx2g', "-Dolist.includeTrend=$trend", "-Djava.library.path=$native", '-cp', $cp,
        'Test.analysis.brazil.OlistBestCsaaDroRunner')
    . (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
    $existing = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object {
        $command = [OlistWindowsProcess]::CommandLine($_.Id)
        $command.Contains($Root) -and $command.Contains('OlistBestCsaaDroRunner')
    })
    if ($existing.Count) { throw "Existing DRO workers: $($existing.Id -join ',')" }
    foreach ($file in @($cfg.java, $cfg.cplexJar, $cfg.mosekJar,
        (Join-Path $lib 'mosek64_11_0.dll'), (Join-Path $cfg.cplexNative 'cplex2211.dll'),
        (Join-Path $Root 'runtime/classes/Test/analysis/brazil/OlistBestCsaaDroRunner.class'))) {
        if (!(Test-Path -LiteralPath $file)) { throw "Missing dependency: $file" }
    }
    if ($CheckOnly) {
        Event 'CHECK_ONLY_PASS independent runtime, MOSEK/CPLEX libraries and process identity; no solves'
        return
    }
    $oldFailure=Join-Path $control 'scheduler_failure.json'
    if(Test-Path -LiteralPath $oldFailure){
        $history=Join-Path $control 'startup_history'
        New-Item -ItemType Directory -Force -Path $history|Out-Null
        Move-Item -LiteralPath $oldFailure -Destination (Join-Path $history "failure_$(Get-Date -Format yyyyMMdd_HHmmss_fff).json")
    }
    do {
        # PS5 ConvertFrom-Json emits a JSON array as one pipeline object; assign directly.
        $parsed = Read-LiveJson (Join-Path $BaseRoot 'control/status.json')
        if ($parsed -isnot [Array] -and $null -ne $parsed.PSObject.Properties['value']) { $parsed=$parsed.value }
        [object[]]$state = $parsed
        $done = @($state | Where-Object state -eq 'COMPLETE').Count
        $failed = @($state | Where-Object state -eq 'FAILED').Count
        Write-Atomic (Join-Path $control 'status.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
            state='WAITING_BASELINES';updated=(Get-Date -Format o);completedBaseTasks=$done;
            failedBaseTasks=$failed;requiredBaseTasks=20;parallel=4;solverThreads=4;
            lambdaGrid=@(0.1,0.25,0.5,1);selection='GLOBAL_OOS_MEAN';formalTrainingOnly=$false}) -Depth 3)
        if ($failed) { throw 'Baseline task failed: cannot select a global winner from incomplete results' }
        if ($state.Count -ne 20) { throw 'Unexpected baseline queue task count' }
        if ($done -lt 20) { Start-Sleep -Seconds 60 }
    } while ($done -lt 20)
    & $cfg.java @common select $BaseRoot $Root
    if ($LASTEXITCODE -ne 0) { throw 'Global selection/full-output audit failed' }
    $method = ([IO.File]::ReadAllLines((Join-Path $Root 'global_selection.tsv'))[1] -split "`t")[0]
    Event "GLOBAL_SELECTED $method all five markets; no per-market family selection"
    $tasks = @(foreach ($r in 0..4) {
        [pscustomobject]@{market=('market_{0:D3}' -f $r);method=$method;state='PENDING';attempt=0;
            pid=0;exitCode=$null;started=$null;finished=$null;process=$null}
    })
    function Save-State {
        Write-Atomic (Join-Path $control 'status.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
            state=$(if(@($tasks | Where-Object state -in @('PENDING','RUNNING')).Count){'RUNNING'}else{'FINISHED'});
            updated=(Get-Date -Format o);contextMethod=$method;parallel=4;solverThreads=4;
            lambdaGrid=@(0.1,0.25,0.5,1);selection='GLOBAL_OOS_MEAN';formalTrainingOnly=$false;
            tasks=@($tasks | Select-Object market,method,state,attempt,pid,exitCode,started,finished)}) -Depth 4)
    }
    foreach ($task in $tasks) {
        & $cfg.java @common audit $BaseRoot $Root $task.market $method
        if ($LASTEXITCODE -eq 0) { $task.state='COMPLETE'; Event "REUSE $($task.market)" }
    }
    while (@($tasks | Where-Object state -in @('PENDING','RUNNING')).Count) {
        foreach ($task in @($tasks | Where-Object state -eq 'RUNNING')) {
            $task.process.Refresh()
            if (!$task.process.HasExited) { continue }
            $task.process.WaitForExit(); $task.exitCode=$task.process.ExitCode
            $task.process.Dispose(); $task.process=$null
            & $cfg.java @common audit $BaseRoot $Root $task.market $method
            if ($LASTEXITCODE -eq 0) { $task.state='COMPLETE' }
            elseif ($task.attempt -lt 2) { $task.state='PENDING' }
            else { $task.state='FAILED' }
            $task.finished=Get-Date -Format o
            Event "$($task.state) $($task.market) exit=$($task.exitCode) attempt=$($task.attempt)"
        }
        $slots=4-@($tasks | Where-Object state -eq 'RUNNING').Count
        if ($slots -gt 0) {
            foreach ($task in @($tasks | Where-Object state -eq 'PENDING' | Select-Object -First $slots)) {
                $task.attempt++
                $stem=Join-Path $control "$($task.market)_$(Get-Date -Format yyyyMMdd_HHmmss_fff)_attempt$($task.attempt)"
                $arguments=@($common)+@('run',$BaseRoot,$Root,$task.market,$method)
                $quoted=@($arguments | ForEach-Object {'"'+$_+'"'})
                try {
                    $task.process=Start-Process -FilePath $cfg.java -ArgumentList $quoted -WorkingDirectory $Root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stem.stdout.log" -RedirectStandardError "$stem.stderr.log"
                    $handle=$task.process.Handle
                    $task.pid=$task.process.Id; $task.state='RUNNING'; $task.started=Get-Date -Format o
                    Event "START $($task.market) method=$method pid=$($task.pid)"
                } catch {
                    $task.state=if($task.attempt -lt 2){'PENDING'}else{'FAILED'}
                    Event "LAUNCH_FAILED $($task.market) $_"
                }
            }
        }
        Save-State
        if (@($tasks | Where-Object state -eq 'RUNNING').Count) { Start-Sleep -Seconds 5 }
    }
    Event "QUEUE_FINISHED complete=$(@($tasks | Where-Object state -eq 'COMPLETE').Count)/5 failed=$(@($tasks | Where-Object state -eq 'FAILED').Count)"
} catch {
    Write-Atomic (Join-Path $control 'scheduler_failure.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
        state='FAILED';updated=(Get-Date -Format o);error=$_.Exception.Message}))
    throw
} finally { $lock.Dispose() }
