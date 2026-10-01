param([string]$Root = (Split-Path $PSScriptRoot -Parent), [switch]$CheckOnly)
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
    $orphans = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine.Contains('OlistContextualRunner')
    })
    if ($orphans.Count) { throw "Existing Olist workers still running (PID $($orphans.ProcessId -join ',')); do not launch duplicates." }
    $cp = (Join-Path $Root 'runtime/classes') + ';' + $cfg.cplexJar + ';' + $cfg.mosekJar
    $base = @("-Xmx$($cfg.heap)", "-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp)
    $code = Invoke-Java ($base + @('Test.analysis.brazil.OlistContextualBatchMain', 'check', $Root))
    if ($code -ne 0) { throw 'Frozen inputs failed audit' }
    & $cfg.python -c 'import sys,numpy,sklearn; print(sys.version); print(numpy.__version__,sklearn.__version__)'
    if ($LASTEXITCODE -ne 0) { throw 'RF dependencies unavailable' }
    $markets = Import-Csv -LiteralPath (Join-Path $Root 'inputs/markets.tsv') -Delimiter "`t"
    $tasks = @()
    foreach ($market in $markets) {
        $properties = @("-Dolist.instance=$(Join-Path $Root $market.instance)", "-Dolist.marketSeed=$($market.market_seed)",
            "-Dolist.rfSeed=$($market.rf_seed)", "-Dolist.python=$($cfg.python)",
            "-Dolist.rfScript=$(Join-Path $Root 'scripts/rf_leaf_weights.py')",
            "-Dolist.threads=$($cfg.solverThreads)", "-Dolist.limit=$($cfg.limitSeconds)")
        $output = Join-Path $Root "results/$($market.market)"
        # Prepare shared immutable metadata once, before the four method processes start.
        $prepare = @("-Xmx$($cfg.heap)") + $properties + @("-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp,
            'Test.analysis.brazil.OlistContextualRunner', 'prepare', $output)
        $code = Invoke-Java $prepare
        if ($code -ne 0) { throw "Protocol preparation failed: $($market.market)" }
        foreach ($method in @('D', 'SAA', 'EXP', 'RF')) {
            $arguments = @("-Xmx$($cfg.heap)") + $properties + @("-Dolist.methods=$method",
                "-Djava.library.path=$($cfg.cplexNative)", '-cp', $cp,
                'Test.analysis.brazil.OlistContextualRunner', 'run', $output, '0', '51')
            $tasks += [pscustomobject]@{ market=$market.market; method=$method; state='PENDING'; attempt=0; pid=0;
                exitCode=$null; started=$null; finished=$null; arguments=$arguments; process=$null }
        }
    }
    Write-Atomic (Join-Path $control 'launch_plan.json') (ConvertTo-Json -InputObject @($tasks | Select-Object market,method,arguments) -Depth 5)
    if ($CheckOnly) { Event "CHECK_ONLY_PASS five inputs, Java, RF dependencies, protocol and $($tasks.Count) launch commands; no solves"; return }
    function Save-State {
        Write-Atomic (Join-Path $control 'status.json') (ConvertTo-Json -InputObject @($tasks | Select-Object market,method,state,attempt,pid,exitCode,started,finished) -Depth 5)
    }
    # Audit old results: an exit code or a marker alone is not enough to skip a task.
    foreach ($task in $tasks) {
        $code = Invoke-Java ($base + @('Test.analysis.brazil.OlistContextualBatchMain', 'audit', $Root, $task.market, $task.method))
        if ($code -eq 0) { $task.state = 'COMPLETE'; Event "REUSE $($task.market) $($task.method)" }
    }
    Save-State
    while (@($tasks | Where-Object { $_.state -in @('PENDING','RUNNING') }).Count) {
        foreach ($task in @($tasks | Where-Object state -eq 'RUNNING')) {
            $task.process.Refresh()
            if (!$task.process.HasExited) { continue }
            $task.exitCode = $task.process.ExitCode
            $task.process.Dispose()
            $task.process = $null
            $code = Invoke-Java ($base + @('Test.analysis.brazil.OlistContextualBatchMain', 'audit', $Root, $task.market, $task.method))
            if ($code -eq 0) { $task.state='COMPLETE' }
            elseif ($task.attempt -lt $cfg.maxAttempts) { $task.state='PENDING' }
            else { $task.state='FAILED' }
            $task.finished = Get-Date -Format o
            Event "$($task.state) $($task.market) $($task.method) exit=$($task.exitCode) attempt=$($task.attempt)"
        }
        $slots = $cfg.maxParallel - @($tasks | Where-Object state -eq 'RUNNING').Count
        foreach ($task in @($tasks | Where-Object state -eq 'PENDING' | Select-Object -First $slots)) {
            $task.attempt++
            $stem = Join-Path $control "$($task.market)_$($task.method)_$(Get-Date -Format yyyyMMdd_HHmmss_fff)_attempt$($task.attempt)"
            # All arguments are separate, quoted tokens; no generated shell command for file operations.
            $quoted = @($task.arguments | ForEach-Object { '"' + $_ + '"' })
            try {
                $task.process = Start-Process -FilePath $cfg.java -ArgumentList $quoted -WorkingDirectory $Root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stem.stdout.log" -RedirectStandardError "$stem.stderr.log"
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
    Event "QUEUE_FINISHED complete=$(@($tasks | Where-Object state -eq 'COMPLETE').Count)/$($tasks.Count) failed=$(@($tasks | Where-Object state -eq 'FAILED').Count)"
} finally { $lock.Dispose() }
