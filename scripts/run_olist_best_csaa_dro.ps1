param([Parameter(Mandatory=$true)][string]$BaseRoot,
      [Parameter(Mandatory=$true)][string]$Root, [switch]$CheckOnly,
      [string]$LambdaGrid='0.1,0.25,0.5,1', [string]$ReuseRoot='', [switch]$FixedRf)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$BaseRoot = (Resolve-Path -LiteralPath $BaseRoot).Path
$Root = (Resolve-Path -LiteralPath $Root).Path
$gridValues=@($LambdaGrid.Split(',') | ForEach-Object {[double]::Parse($_,[Globalization.CultureInfo]::InvariantCulture)})
$cfg = [IO.File]::ReadAllText((Join-Path $BaseRoot 'config.json')) | ConvertFrom-Json
$markets = @(Import-Csv -LiteralPath (Join-Path $BaseRoot 'inputs/markets.tsv') -Delimiter "`t")
$baseMethods = if ($cfg.methods) { @($cfg.methods) } else { @('D','SAA','EXP','RF') }
$requiredBaseTasks = $markets.Count * $baseMethods.Count
if (!$markets.Count -or 'RF' -notin $baseMethods -or (!$FixedRf -and 'EXP' -notin $baseMethods)) { throw 'CSAA baseline manifest/methods invalid' }
$selectionRule=if($FixedRf){'USER_FIXED_RF'}else{'GLOBAL_OOS_MEAN'}
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
    $fixedTrend = if ($cfg.fixedTrend104 -eq $true) { 'true' } else { 'false' }
    $gridOptions=@("-Dolist.lambdaGrid=$LambdaGrid", "-Dolist.fixedRf=$($FixedRf.IsPresent.ToString().ToLowerInvariant())", "-Dolist.fixedTrend104=$fixedTrend")
    if($ReuseRoot){$ReuseRoot=(Resolve-Path -LiteralPath $ReuseRoot).Path;$gridOptions+= "-Dolist.reuseRoot=$ReuseRoot"}
    $common = @('-Xmx2g') + $gridOptions + @("-Dolist.includeTrend=$trend", "-Djava.library.path=$native", '-cp', $cp,
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
        & $cfg.java @common selfcheck
        if($LASTEXITCODE -ne 0){throw 'DRO grid selfcheck failed'}
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
            failedBaseTasks=$failed;requiredBaseTasks=$requiredBaseTasks;parallel=4;solverThreads=4;
            lambdaGrid=$gridValues;selection=$selectionRule;formalTrainingOnly=$FixedRf.IsPresent}) -Depth 3)
        if ($failed -and !$FixedRf) { throw 'Baseline task failed: cannot select a global winner from incomplete results' }
        if ($state.Count -ne $requiredBaseTasks) { throw 'Unexpected baseline queue task count' }
        $baseReady = if($FixedRf){@($state|Where-Object state -notin @('COMPLETE','FAILED')).Count -eq 0}else{$done -eq $requiredBaseTasks}
        if (!$baseReady) { Start-Sleep -Seconds 30 }
    } while (!$baseReady)
    & $cfg.java @common select $BaseRoot $Root
    if ($LASTEXITCODE -ne 0) { throw 'Global selection/full-output audit failed' }
    $method = ([IO.File]::ReadAllLines((Join-Path $Root 'global_selection.tsv'))[1] -split "`t")[0]
    Event "GLOBAL_SELECTED $method all $($markets.Count) markets; no per-market family selection"
    $tasks = @(foreach ($market in $markets) {
        [pscustomobject]@{market=$market.market;method=$method;state='PENDING';attempt=0;
            pid=0;exitCode=$null;started=$null;finished=$null;process=$null}
    })
    function Save-State {
        Write-Atomic (Join-Path $control 'status.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
            state=$(if(@($tasks | Where-Object state -in @('PENDING','RUNNING')).Count){'RUNNING'}elseif(@($tasks | Where-Object state -ne 'COMPLETE').Count){'FINISHED_WITH_FAILURES'}else{'FINISHED'});
            updated=(Get-Date -Format o);contextMethod=$method;parallel=4;solverThreads=4;
            lambdaGrid=$gridValues;selection=$selectionRule;formalTrainingOnly=$FixedRf.IsPresent;
            tasks=@($tasks | Select-Object market,method,state,attempt,pid,exitCode,started,finished)}) -Depth 4)
    }
    foreach ($task in $tasks) {
        if ($FixedRf) {
            $rfState=@($state | Where-Object { $_.market -eq $task.market -and $_.method -eq 'RF' })
            if($rfState.Count -ne 1 -or $rfState[0].state -ne 'COMPLETE') {
                $task.state='BLOCKED_BASELINE'; Event "BLOCKED_BASELINE $($task.market) RF incomplete"; continue
            }
            $auditArgs=@('-Xmx2g',"-Dolist.includeTrend=$trend","-Dolist.fixedTrend104=$fixedTrend",'-cp',$cp,
                'Test.analysis.brazil.OlistContextualBatchMain','audit',$BaseRoot,$task.market,'RF')
            & $cfg.java @auditArgs
            if($LASTEXITCODE -ne 0){$task.state='BLOCKED_BASELINE';Event "BLOCKED_BASELINE $($task.market) RF output audit failed";continue}
        }
        & $cfg.java @common audit $BaseRoot $Root $task.market $method
        if ($LASTEXITCODE -eq 0) { $task.state='COMPLETE'; Event "REUSE $($task.market)" }
    }
    Save-State
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
    Save-State
    Event "QUEUE_FINISHED complete=$(@($tasks | Where-Object state -eq 'COMPLETE').Count)/$($markets.Count) failed_or_blocked=$(@($tasks | Where-Object state -ne 'COMPLETE').Count)"
} catch {
    Write-Atomic (Join-Path $control 'scheduler_failure.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
        state='FAILED';updated=(Get-Date -Format o);error=$_.Exception.Message}))
    throw
} finally { $lock.Dispose() }
