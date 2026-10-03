param([string]$Root = (Split-Path $PSScriptRoot -Parent), [switch]$CheckOnly,
      [switch]$AdoptRunning, [switch]$OverlapFixedRf)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
$cfg = Get-Content -LiteralPath (Join-Path $Root 'config.json') -Encoding UTF8 -Raw | ConvertFrom-Json
$control = Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
# The lock is released by the OS even after a scheduler crash.
$lock = [IO.File]::Open((Join-Path $control 'scheduler.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
function Write-Atomic($Path, $Text) {
    for ($attempt = 0; ; $attempt++) {
        try {
            [IO.File]::WriteAllText("$Path.tmp", $Text, (New-Object Text.UTF8Encoding($false)))
            if (Test-Path -LiteralPath $Path) { [IO.File]::Replace("$Path.tmp", $Path, [NullString]::Value) }
            else { [IO.File]::Move("$Path.tmp", $Path) }
            return
        } catch {
            $cause = $_.Exception.GetBaseException()
            if ($attempt -ge 3 -or !($cause -is [IO.IOException] -or $cause -is [UnauthorizedAccessException])) { throw }
            Start-Sleep -Milliseconds (100 * ($attempt + 1))
        }
    }
}
function Event($Message) {
    Add-Content -LiteralPath (Join-Path $control 'events.log') -Value ((Get-Date -Format o) + ' ' + $Message)
    Write-Host $Message
}
function Invoke-Java($Arguments) {
    & $cfg.java @Arguments | Out-Host
    return $LASTEXITCODE
}
try {
    foreach ($file in @($cfg.java, $cfg.cplexJar, $cfg.mosekJar, $cfg.python, (Join-Path $Root 'scripts/rf_leaf_weights.py'))) {
        if (!(Test-Path -LiteralPath $file)) { throw "Runtime file missing: $file" }
    }
    if (!(Test-Path -LiteralPath (Join-Path $cfg.cplexNative 'cplex2211.dll'))) { throw 'CPLEX native DLL missing' }
    if ($cfg.maxParallel -lt 1 -or $cfg.solverThreads -lt 1 -or $cfg.limitSeconds -lt 1 -or $cfg.maxAttempts -lt 1) { throw 'Invalid runtime settings' }
    # Refuse duplicate schedulers/workers after interruption; never kill unrelated Java processes.
    . (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
    $orphans = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object {
        $command = [OlistWindowsProcess]::CommandLine($_.Id)
        $command.Contains($Root) -and $command.Contains('OlistContextualRunner')
    })
    if ($orphans.Count -and !$AdoptRunning) { throw "Existing Olist workers still running (PID $($orphans.Id -join ',')); do not launch duplicates." }
    $savedTasks=if($AdoptRunning){Get-Content -LiteralPath (Join-Path $control 'status.json') -Raw|ConvertFrom-Json}else{@()}
    $cp = (Join-Path $Root 'runtime/classes') + ';' + $cfg.cplexJar + ';' + $cfg.mosekJar
    $trend = if ($cfg.includeTrend -eq $true) { 'true' } else { 'false' }
    $fixedTrend = if ($cfg.fixedTrend104 -eq $true) { 'true' } else { 'false' }
    $base = @("-Xmx$($cfg.heap)", "-Dolist.includeTrend=$trend", "-Dolist.fixedTrend104=$fixedTrend", "-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp)
    $code = Invoke-Java ($base + @('Test.analysis.brazil.OlistContextualBatchMain', 'check', $Root))
    if ($code -ne 0) { throw 'Frozen inputs failed audit' }
    & $cfg.python -c 'import sys,numpy,sklearn; print(sys.version); print(numpy.__version__,sklearn.__version__)'
    if ($LASTEXITCODE -ne 0) { throw 'RF dependencies unavailable' }
    $markets = Import-Csv -LiteralPath (Join-Path $Root 'inputs/markets.tsv') -Delimiter "`t"
    $methods = if ($cfg.methods) { @($cfg.methods) } else { @('D','SAA','EXP','RF') }
    if (!$methods.Count -or @($methods | Where-Object { $_ -notin @('D','SAA','EXP','RF') }).Count) { throw 'Invalid baseline methods' }
    $tasks = @()
    foreach ($market in $markets) {
        $properties = @("-Dolist.instance=$(Join-Path $Root $market.instance)", "-Dolist.marketSeed=$($market.market_seed)",
            "-Dolist.includeTrend=$trend",
            "-Dolist.fixedTrend104=$fixedTrend",
            "-Dolist.rfSeed=$($market.rf_seed)", "-Dolist.python=$($cfg.python)",
            "-Dolist.marketLabel=$(if($cfg.marketLabel){$cfg.marketLabel}else{'current_factory_50pct_coverage_mqc015035_spot23_minH'})",
            "-Dolist.rfScript=$(Join-Path $Root 'scripts/rf_leaf_weights.py')",
            "-Dolist.threads=$($cfg.solverThreads)", "-Dolist.limit=$($cfg.limitSeconds)")
        $output = Join-Path $Root "results/$($market.market)"
        # Prepare shared immutable metadata once, before the four method processes start.
        $prepare = @("-Xmx$($cfg.heap)") + $properties + @("-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp,
            'Test.analysis.brazil.OlistContextualRunner', 'prepare', $output)
        if(!$AdoptRunning){
            $code = Invoke-Java $prepare
            if ($code -ne 0) { throw "Protocol preparation failed: $($market.market)" }
        }
        foreach ($method in $methods) {
            $arguments = @("-Xmx$($cfg.heap)") + $properties + @("-Dolist.methods=$method",
                "-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp,
                'Test.analysis.brazil.OlistContextualRunner', 'run', $output, '0', '51')
            $tasks += [pscustomobject]@{ market=$market.market; method=$method; state='PENDING'; attempt=0; pid=0;
                exitCode=$null; started=$null; finished=$null; arguments=$arguments; process=$null }
        }
    }
    Write-Atomic (Join-Path $control 'launch_plan.json') (ConvertTo-Json -InputObject @($tasks | Select-Object market,method,arguments) -Depth 5)
    if ($CheckOnly) { Event "CHECK_ONLY_PASS $(@($markets).Count) inputs, Java, RF dependencies, protocol and $($tasks.Count) launch commands; no solves"; return }
    $droLock=$null
    if($OverlapFixedRf){
        if(@($methods).Count -ne 1 -or @($methods)[0] -ne 'RF'){throw 'Overlap requires the fixed RF-only baseline'}
        $dro=Join-Path $Root 'dro_rf';$droControl=Join-Path $dro 'control'
        New-Item -ItemType Directory -Force -Path $droControl|Out-Null
        $droLock=[IO.File]::Open((Join-Path $droControl 'scheduler.lock'),'OpenOrCreate','ReadWrite','None')
        $follow=Get-Content -LiteralPath (Join-Path $Root 'followup.json') -Raw|ConvertFrom-Json
        $grid=$follow.lambdaGrid
        $lib=Split-Path $cfg.mosekJar -Parent
        $env:Path="$($cfg.cplexNative);$lib;$(Split-Path $cfg.java -Parent);$env:Path"
        $env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS=[string]$cfg.solverThreads
        $droCp=(Join-Path $dro 'runtime/classes')+';'+$cfg.cplexJar+';'+$cfg.mosekJar
        $droCommon=@("-Xmx$($cfg.heap)","-Dolist.lambdaGrid=$grid",'-Dolist.fixedRf=true',
            "-Dolist.fixedTrend104=$fixedTrend","-Dolist.includeTrend=$trend",
            "-Dolist.threads=$($cfg.solverThreads)","-Dolist.limit=$($cfg.limitSeconds)",
            "-Djava.library.path=$($cfg.cplexNative);$lib",'-cp',$droCp,'Test.analysis.brazil.OlistBestCsaaDroRunner')
        $liveDro=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object {
            $command=[OlistWindowsProcess]::CommandLine($_.Id)
            $command.Contains($dro) -and $command.Contains('OlistBestCsaaDroRunner')
        })
        $savedDroTasks=@()
        if($AdoptRunning -and (Test-Path -LiteralPath (Join-Path $droControl 'status.json'))){
            $savedDroState=Get-Content -LiteralPath (Join-Path $droControl 'status.json') -Raw|ConvertFrom-Json
            $savedDroTasks=@($savedDroState.tasks)
        }
        if($liveDro.Count -and !$AdoptRunning){throw 'Existing DRO workers: use AdoptRunning to take over'}
        if((Invoke-Java ($droCommon+@('selfcheck'))) -ne 0){throw 'DRO overlap preflight failed'}
        if(!$liveDro.Count -and (Invoke-Java ($droCommon+@('select',$Root,$dro))) -ne 0){throw 'Fixed RF selection failed'}
        foreach($market in $markets){
            $tasks+=[pscustomobject]@{market=$market.market;method='RF_CHI2';state='WAITING_BASELINE';attempt=0;pid=0;
                exitCode=$null;started=$null;finished=$null;arguments=($droCommon+@('run',$Root,$dro,$market.market,'RF'));process=$null}
        }
    }
    function Audit-Task($Task){
        if($Task.method -eq 'RF_CHI2'){return (Invoke-Java ($droCommon+@('audit',$Root,$dro,$Task.market,'RF')))}
        return (Invoke-Java ($base+@('Test.analysis.brazil.OlistContextualBatchMain','audit',$Root,$Task.market,$Task.method)))
    }
    function Save-State {
        Write-Atomic (Join-Path $control 'status.json') (ConvertTo-Json -InputObject @($tasks | Where-Object method -ne 'RF_CHI2' | Select-Object market,method,state,attempt,pid,exitCode,started,finished) -Depth 5)
        if($OverlapFixedRf){
            $robust=@($tasks|Where-Object method -eq 'RF_CHI2')
            $active=@($tasks|Where-Object state -in @('PENDING','RUNNING','WAITING_BASELINE')).Count
            Write-Atomic (Join-Path $droControl 'status.json') (ConvertTo-Json -InputObject ([pscustomobject]@{
                state=$(if($active){'RUNNING'}elseif(@($robust|Where-Object state -ne 'COMPLETE').Count){'FINISHED_WITH_FAILURES'}else{'FINISHED'});
                updated=(Get-Date -Format o);contextMethod='RF';parallel=$cfg.maxParallel;solverThreads=$cfg.solverThreads;
                sharedBaselineSlots=$true;selection='USER_FIXED_RF';formalTrainingOnly=$true;
                lambdaGrid=@($grid.Split(',')|ForEach-Object {[double]::Parse($_,[Globalization.CultureInfo]::InvariantCulture)});
                tasks=@($robust|Select-Object market,@{n='method';e={'RF'}},state,attempt,pid,exitCode,started,finished)}) -Depth 5)
        }
    }
    # Audit old results: an exit code or a marker alone is not enough to skip a task.
    foreach ($task in $tasks) {
        if($task.method -eq 'RF_CHI2'){continue}
        $saved=@($savedTasks|Where-Object {$_.market -eq $task.market -and $_.method -eq $task.method})
        if($saved.Count -gt 1){throw 'Duplicate saved baseline task'}
        if($saved.Count -eq 1){
            foreach($name in @('attempt','pid','exitCode','started','finished')){$task.$name=$saved[0].$name}
            $worker=@($orphans|Where-Object Id -eq $task.pid)
            if($worker.Count){
                $command=[OlistWindowsProcess]::CommandLine($task.pid)
                if($saved[0].state -ne 'RUNNING' -or !$command.Contains("results\$($task.market)") -or
                    [Math]::Abs(($worker[0].StartTime-([datetime]$task.started)).TotalSeconds) -gt 5){throw 'Worker adoption identity mismatch'}
                $task.process=$worker[0];$heldHandle=$task.process.Handle;$task.state='RUNNING'
                Event "ADOPT $($task.market) $($task.method) pid=$($task.pid)";continue
            }
        }
        $code = Audit-Task $task
        if ($code -eq 0) { $task.state = 'COMPLETE'; Event "REUSE $($task.market) $($task.method)" }
        elseif($task.attempt -ge $cfg.maxAttempts){$task.state='FAILED'}
    }
    if(@($orphans|Where-Object {$_.Id -notin @($tasks|Where-Object state -eq 'RUNNING'|ForEach-Object pid)}).Count){throw 'Unclaimed baseline worker; refuse duplicate launch'}
    if($OverlapFixedRf -and $AdoptRunning){
        foreach($task in @($tasks|Where-Object method -eq 'RF_CHI2')){
            $saved=@($savedDroTasks|Where-Object {$_.market -eq $task.market -and $_.method -eq 'RF'})
            if($saved.Count -gt 1){throw 'Duplicate saved DRO task'}
            if($saved.Count -ne 1){continue}
            foreach($name in @('attempt','pid','exitCode','started','finished')){$task.$name=$saved[0].$name}
            $worker=@($liveDro|Where-Object Id -eq $task.pid)
            if(!$worker.Count){continue}
            $command=[OlistWindowsProcess]::CommandLine($task.pid)
            $runTail='"?run"?\s+"?'+[regex]::Escape($Root)+'"?\s+"?'+
                [regex]::Escape($dro)+'"?\s+"?'+[regex]::Escape($task.market)+'"?\s+"?RF"?(?:\s|$)'
            if($saved[0].state -ne 'RUNNING' -or !$command.Contains($dro) -or
                $command -notmatch $runTail -or
                !$command.Contains("-Dolist.lambdaGrid=$grid") -or
                !$command.Contains("-Dolist.threads=$($cfg.solverThreads)") -or
                !$command.Contains("-Dolist.includeTrend=$trend") -or
                !$command.Contains("-Dolist.fixedTrend104=$fixedTrend") -or
                !$command.Contains("-Dolist.limit=$($cfg.limitSeconds)") -or
                [Math]::Abs(($worker[0].StartTime-([datetime]$task.started)).TotalSeconds) -gt 5){
                throw 'DRO worker adoption identity/configuration mismatch'
            }
            $task.process=$worker[0];$heldHandle=$task.process.Handle;$task.state='RUNNING'
            Event "ADOPT $($task.market) RF_CHI2 pid=$($task.pid)"
        }
        if(@($liveDro|Where-Object {$_.Id -notin @($tasks|Where-Object state -eq 'RUNNING'|ForEach-Object pid)}).Count){
            throw 'Unclaimed DRO worker; refuse duplicate launch'
        }
    }
    Save-State
    while (@($tasks | Where-Object { $_.state -in @('PENDING','RUNNING','WAITING_BASELINE') }).Count) {
        foreach ($task in @($tasks | Where-Object state -eq 'RUNNING')) {
            $task.process.Refresh()
            if (!$task.process.HasExited) { continue }
            $task.process.WaitForExit()
            $task.exitCode = $task.process.ExitCode
            $task.process.Dispose()
            $task.process = $null
            $code = Audit-Task $task
            if ($code -eq 0) { $task.state='COMPLETE' }
            elseif ($task.attempt -lt $cfg.maxAttempts) { $task.state='PENDING' }
            else { $task.state='FAILED' }
            $task.finished = Get-Date -Format o
            Event "$($task.state) $($task.market) $($task.method) exit=$($task.exitCode) attempt=$($task.attempt)"
        }
        foreach($task in @($tasks|Where-Object state -eq 'WAITING_BASELINE')){
            $rf=@($tasks|Where-Object {$_.market -eq $task.market -and $_.method -eq 'RF'})[0]
            if($rf.state -eq 'FAILED'){$task.state='BLOCKED_BASELINE';Event "BLOCKED_BASELINE $($task.market)"}
            elseif($rf.state -eq 'COMPLETE'){
                if((Audit-Task $task) -eq 0){$task.state='COMPLETE';Event "REUSE $($task.market) RF_CHI2"}
                else{$task.state='PENDING'}
            }
        }
        $slots = $cfg.maxParallel - @($tasks | Where-Object state -eq 'RUNNING').Count
        foreach ($task in @($tasks | Where-Object { $_.state -eq 'PENDING' -and $slots -gt 0 } | Select-Object -First ([Math]::Max(0,$slots)))) {
            $task.attempt++
            $stem = Join-Path $control "$($task.market)_$($task.method)_$(Get-Date -Format yyyyMMdd_HHmmss_fff)_attempt$($task.attempt)"
            # All arguments are separate, quoted tokens; no generated shell command for file operations.
            $quoted = @($task.arguments | ForEach-Object { '"' + $_ + '"' })
            try {
                $working=if($task.method -eq 'RF_CHI2'){$dro}else{$Root}
                $task.process = Start-Process -FilePath $cfg.java -ArgumentList $quoted -WorkingDirectory $working -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stem.stdout.log" -RedirectStandardError "$stem.stderr.log"
                $heldHandle = $task.process.Handle
                $task.pid=$task.process.Id; $task.state='RUNNING'; $task.started=Get-Date -Format o
                Event "START $($task.market) $($task.method) pid=$($task.pid)"
            } catch {
                $task.state = if ($task.attempt -lt $cfg.maxAttempts) { 'PENDING' } else { 'FAILED' }
                $task.finished=Get-Date -Format o; Event "LAUNCH_FAILED $($task.market) $($task.method) next=$($task.state): $_"
            }
        }
        Save-State
        if (@($tasks | Where-Object state -eq 'RUNNING').Count) { Start-Sleep -Seconds 5 }
    }
    Save-State
    Event "QUEUE_FINISHED complete=$(@($tasks | Where-Object state -eq 'COMPLETE').Count)/$($tasks.Count) failed=$(@($tasks | Where-Object state -eq 'FAILED').Count)"
} finally { if($null -ne $droLock){$droLock.Dispose()};$lock.Dispose() }
