param([string]$TaskRoot, [string]$ExperimentRoot, [ValidateRange(1, 4)][int]$MaxParallel = 4)
$ErrorActionPreference = 'Stop'
$java = 'D:\Java\jdk-21\bin\java.exe'
$native = 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\ccx\TSPP_SVU\lib'
$classpath = "$TaskRoot\bin;D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\ccx\TSPP_SVU\lib\mosek.jar"
$output = $ExperimentRoot
$control = Join-Path $ExperimentRoot 'control\four_thread_retry_20260930'
New-Item -ItemType Directory -Force -Path $control | Out-Null
trap {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $control 'controller_error.log')
    exit 1
}
$env:Path = "$native;D:\Java\jdk-21\bin;$env:Path"
$env:OMP_NUM_THREADS = '4'
$env:OPENBLAS_NUM_THREADS = '1'
$env:MKL_NUM_THREADS = '1'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot
$queue = [System.Collections.Generic.Queue[object]]::new()
$running = [System.Collections.Generic.List[object]]::new()
$events = Join-Path $control 'events.csv'
function Add-Event($task, $state, $exitCode) {
    [pscustomobject]@{time=[DateTime]::Now.ToString('o');method=$task.method;rep=$task.rep;attempt=$task.attempt;state=$state;exitCode=$exitCode} |
        Export-Csv -LiteralPath $events -Append -NoTypeInformation -Encoding UTF8
}
$attempts = @{}
if (Test-Path -LiteralPath $events) {
    foreach ($entry in Import-Csv -LiteralPath $events) {
        $key = "$($entry.method)/$($entry.rep)"
        $attempts[$key] = [Math]::Max([int]$attempts[$key], [int]$entry.attempt)
    }
}
# A controller handoff adopts existing Java supervisors; it never stops solvers.
$adoptFile = Join-Path $control 'adopt_running.json'
if (Test-Path -LiteralPath $adoptFile) {
    foreach ($entry in (Get-Content -LiteralPath $adoptFile -Raw | ConvertFrom-Json)) {
        $process = Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
        if (-not $process -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startTicks) { continue }
        $heldHandle = $process.Handle
        $relative = if ($entry.method -eq 'C-Chi2') { 'experiment2\primary\C-Chi2\rep_{0:D3}' -f $entry.rep } else { 'experiment1\rep_{0:D3}\{1}' -f $entry.rep,$entry.method }
        $task = [pscustomobject]@{rep=[int]$entry.rep;method=$entry.method;target=(Join-Path $output $relative);attempt=[int]$entry.attempt}
        $running.Add([pscustomobject]@{task=$task;process=$process})
        Add-Event $task 'ADOPTED_RUNNING' ''
    }
    Move-Item -LiteralPath $adoptFile -Destination (Join-Path $control ("adopted_" + [DateTime]::Now.ToString('yyyyMMdd_HHmmss_fff') + '.json'))
}
foreach ($rep in 0..24) {
    foreach ($method in @('C-Chi2','CSAA-Exp','CSAA-Gau','CSAA-Epa','RF-CSAA')) {
        $relative = if ($method -eq 'C-Chi2') { 'experiment2\primary\C-Chi2\rep_{0:D3}' -f $rep } else { 'experiment1\rep_{0:D3}\{1}' -f $rep,$method }
        if (Test-Path -LiteralPath (Join-Path $ExperimentRoot "$relative\complete.txt")) { continue }
        $target = Join-Path $output $relative
        if (Test-Path -LiteralPath (Join-Path $target 'complete.txt')) { continue }
        if (@($running | Where-Object { $_.task.rep -eq $rep -and $_.task.method -eq $method }).Count -gt 0) { continue }
        $queue.Enqueue([pscustomobject]@{rep=$rep;method=$method;target=$target;attempt=[int]$attempts["$method/$rep"]})
    }
}
$initial = $queue.Count
"queued=$initial parallel=$MaxParallel solverThreads=4 output=$output" | Set-Content (Join-Path $control 'plan.txt')
while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    while ($queue.Count -gt 0 -and $running.Count -lt $MaxParallel) {
        $task = $queue.Dequeue()
        if (Test-Path -LiteralPath (Join-Path $task.target 'complete.txt')) {
            Add-Event $task 'SKIPPED_COMPLETE' ''
            continue
        }
        $task.attempt++
        $name = '{0}_rep{1:D3}_attempt{2:D4}' -f $task.method,$task.rep,$task.attempt
        $arguments = @(('"-Djava.library.path='+$native+'"'),'-cp',('"'+$classpath+'"'))
        if ($task.method -eq 'C-Chi2') {
            $arguments += @('Test.analysis.synthetic.TRBSVUExperiment2IdeMain',
                ('"--input='+$ExperimentRoot+'\input"'),('"--experiment1-output='+$ExperimentRoot+'\experiment1"'),
                ('"--output='+$output+'\experiment2"'),'--phase=primary','--methods=C-Chi2','--lambda-grid=0.1,0.25,0.5,1,2')
        } else {
            $arguments += @('Test.analysis.synthetic.TRBSVUExperiment1IdeMain',
                ('"--input='+$ExperimentRoot+'\input"'),('"--output='+$output+'\experiment1"'),
                '--validation-origins=25',('--methods='+$task.method))
        }
        $arguments += @('--parallel=1','--solver-threads=4','--limit-seconds=14400',('--replications='+$task.rep))
        try {
            $process = Start-Process -FilePath $java -ArgumentList ($arguments -join ' ') -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $control "$name.stdout.log") -RedirectStandardError (Join-Path $control "$name.stderr.log")
            # Hold a handle before exit so asynchronous ExitCode remains available.
            $heldHandle = $process.Handle
            $running.Add([pscustomobject]@{task=$task;process=$process})
            Add-Event $task 'STARTED' ''
        } catch {
            Add-Event $task 'START_FAILED_RETRY_QUEUED' ''
            $_ | Out-String | Set-Content -LiteralPath (Join-Path $control "$name.start_error.log")
            $queue.Enqueue($task)
            break
        }
    }
    Start-Sleep -Seconds 10
    foreach ($item in @($running.ToArray())) {
        if (-not $item.process.HasExited) { continue }
        $item.process.WaitForExit()
        $code = $item.process.ExitCode
        if (($null -eq $code -or $code -eq 0) -and (Test-Path -LiteralPath (Join-Path $item.task.target 'complete.txt'))) {
            $state = if ($null -eq $code) { 'COMPLETE_EXIT_UNAVAILABLE' } else { 'COMPLETE' }
            Add-Event $item.task $state $code
        } else {
            Add-Event $item.task 'RETRY_QUEUED' $code
            $queue.Enqueue($item.task)
        }
        [void]$running.Remove($item)
        $item.process.Dispose()
    }
    @("state=RUNNING", "updated=$([DateTime]::Now.ToString('o'))", "queued=$($queue.Count)", "running=$($running.Count)", "parallel=$MaxParallel", 'solverThreads=4') |
        Set-Content -LiteralPath (Join-Path $control 'status.txt')
}
@('state=FINISHED',"ended=$([DateTime]::Now.ToString('o'))", "initialTasks=$initial") | Set-Content (Join-Path $control 'status.txt')
